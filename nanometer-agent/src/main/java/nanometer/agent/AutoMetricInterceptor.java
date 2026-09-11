package nanometer.agent;

import nanometer.buffer.MetricRingBuffer;
import nanometer.model.RelationalMetricEvent;
import nanometer.profiling.JfrProfileSampler;
import nanometer.sampling.AdaptiveSampler;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * High-performance bytecode interceptor attaching to method execution boundaries.
 *
 * <p>Two entry points share one implementation of the recording logic:
 * <ul>
 *   <li>{@link #onEnter} and {@link #onExit} are the {@link Advice} hooks the Java agent inlines
 *       into instrumented methods. Inlining generates no auxiliary classes, which is what lets the
 *       agent work on a JVM that forbids reflection-based class injection.</li>
 *   <li>{@link #intercept(Method, Callable)} is the delegation-based entry point, kept for
 *       programmatic use and for callers that wrap a method by hand.</li>
 * </ul>
 * Both funnel into {@link #enterSpan} and {@link #exitSpan}, so the span bookkeeping and the
 * sampling decision exist exactly once.
 */
@AICore(sensitivity = "High", note = "Hot-path bytecode interceptor tracking thread execution spans")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.THREAD_LOCAL, note = "ThreadLocal span tracking with lock-free ring buffer dispatch")
public class AutoMetricInterceptor {

    private static final int INITIAL_STACK_DEPTH = 64;

    /**
     * Per-thread stack of open spans. Pre-sized and reused so a steady-state call allocates
     * nothing; it grows only for call graphs deeper than the current capacity.
     *
     * <p>Holding the caller's class and method name here is what lets an event carry its own call
     * edge. The aggregator previously rebuilt edges from a map of every span id ever seen, which
     * grew without bound for the lifetime of the process.
     */
    private static final class SpanStack {
        private long[] spanIds = new long[INITIAL_STACK_DEPTH];
        private String[] classNames = new String[INITIAL_STACK_DEPTH];
        private String[] methodNames = new String[INITIAL_STACK_DEPTH];
        private int depth;

        private long traceIdHigh;
        private long traceIdLow;

        /**
         * Wall clock and monotonic reading taken once when the trace opens. Every span's start
         * timestamp derives from these, so a trace costs one currentTimeMillis rather than one per
         * span, and a child's timestamp stays consistent with its parent's.
         */
        private long rootWallMillis;
        private long rootNanos;

        void push(long spanId, String className, String methodName) {
            if (depth == spanIds.length) {
                int grown = spanIds.length * 2;
                long[] ids = new long[grown];
                String[] cns = new String[grown];
                String[] mns = new String[grown];
                System.arraycopy(spanIds, 0, ids, 0, depth);
                System.arraycopy(classNames, 0, cns, 0, depth);
                System.arraycopy(methodNames, 0, mns, 0, depth);
                spanIds = ids;
                classNames = cns;
                methodNames = mns;
            }
            spanIds[depth] = spanId;
            classNames[depth] = className;
            methodNames[depth] = methodName;
            depth++;
        }
    }

    private static final ThreadLocal<SpanStack> SPANS = ThreadLocal.withInitial(SpanStack::new);

    private static volatile MetricRingBuffer buffer = MetricRingBuffer.createDefault();
    private static volatile AdaptiveSampler sampler = new AdaptiveSampler();
    private static volatile JfrProfileSampler profileSampler = new JfrProfileSampler();

    public static void setBuffer(MetricRingBuffer ringBuffer) {
        buffer = ringBuffer;
    }

    public static MetricRingBuffer getBuffer() {
        return buffer;
    }

    public static void setSampler(AdaptiveSampler adaptiveSampler) {
        sampler = adaptiveSampler;
    }

    public static AdaptiveSampler getSampler() {
        return sampler;
    }

    public static void setProfileSampler(JfrProfileSampler profiler) {
        profileSampler = profiler;
    }

    public static JfrProfileSampler getProfileSampler() {
        return profileSampler;
    }

    /**
     * Opens a span on the current thread and returns the monotonic start reading to hand back to
     * {@link #exitSpan}. Every call must be paired with exactly one {@code exitSpan}.
     */
    public static long enterSpan(String className, String methodName) {
        SpanStack stack = SPANS.get();
        long startNanos = System.nanoTime();

        if (stack.depth == 0) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            long high = rnd.nextLong();
            long low = rnd.nextLong();
            if (high == 0L && low == 0L) {
                low = 1L;
            }
            stack.traceIdHigh = high;
            stack.traceIdLow = low;
            stack.rootWallMillis = System.currentTimeMillis();
            stack.rootNanos = startNanos;
        }

        long spanId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        stack.push(spanId, className, methodName);
        return startNanos;
    }

    /**
     * Closes the span opened by {@link #enterSpan} and records it if the sampler keeps it.
     *
     * @param thrown the throwable that left the method, or {@code null} if it returned normally
     */
    @AIPerformance(constraint = "Minimal execution overhead, atomic thread correlation")
    public static void exitSpan(String className, String methodName, long startNanos, @Nullable Throwable thrown) {
        long durationNs = System.nanoTime() - startNanos;

        SpanStack stack = SPANS.get();
        if (stack.depth == 0) {
            // Unbalanced exit. Dropping this span is preferable to corrupting the stack for every
            // later call on this thread.
            return;
        }

        stack.depth--;
        int frame = stack.depth;
        long currentSpanId = stack.spanIds[frame];
        stack.classNames[frame] = null;
        stack.methodNames[frame] = null;

        long parentSpanId = frame > 0 ? stack.spanIds[frame - 1] : 0L;
        String parentClassName = frame > 0 ? stack.classNames[frame - 1] : RelationalMetricEvent.NO_PARENT;
        String parentMethodName = frame > 0 ? stack.methodNames[frame - 1] : RelationalMetricEvent.NO_PARENT;

        long traceIdHigh = stack.traceIdHigh;
        long traceIdLow = stack.traceIdLow;
        long startTimestamp = stack.rootWallMillis + (startNanos - stack.rootNanos) / 1_000_000L;

        if (frame == 0) {
            SPANS.remove();
        }

        String exceptionType = thrown == null
                ? RelationalMetricEvent.NO_EXCEPTION
                : thrown.getClass().getSimpleName();

        // On-demand profiling if slow
        if (durationNs >= 50_000_000L) {
            profileSampler.recordStackTrace(Thread.currentThread().getStackTrace(), durationNs);
        }

        // Adaptive tail sampling check
        if (sampler.shouldSample(durationNs, exceptionType)) {
            buffer.offer(new RelationalMetricEvent(
                    traceIdHigh,
                    traceIdLow,
                    parentSpanId,
                    currentSpanId,
                    className,
                    methodName,
                    parentClassName,
                    parentMethodName,
                    durationNs,
                    exceptionType,
                    startTimestamp
            ));
        }
    }

    /**
     * {@link Advice} entry hook. The body is inlined into every instrumented method, so it must
     * not reference anything the instrumented class cannot see.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onEnter(@Advice.Origin("#t") String className,
                               @Advice.Origin("#m") String methodName) {
        return enterSpan(className, methodName);
    }

    /**
     * {@link Advice} exit hook, invoked for both normal and exceptional returns.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Origin("#t") String className,
                              @Advice.Origin("#m") String methodName,
                              @Advice.Enter long startNanos,
                              @Advice.Thrown @Nullable Throwable thrown) {
        exitSpan(className, methodName, startNanos, thrown);
    }

    @RuntimeType
    @AIPerformance(constraint = "Minimal execution overhead, atomic thread correlation")
    public static @Nullable Object intercept(@Origin Method method, @SuperCall Callable<?> callable) throws Throwable {
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        long startNanos = enterSpan(className, methodName);
        Throwable thrown = null;

        try {
            return callable.call();
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            exitSpan(className, methodName, startNanos, thrown);
        }
    }
}

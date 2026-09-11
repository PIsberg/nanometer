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
 *   <li>{@link #onEnter()} and {@link #onExit} are the {@link Advice} hooks the Java agent inlines
 *       into instrumented methods. Inlining generates no auxiliary classes, which is what lets the
 *       agent work on a JVM that forbids reflection-based class injection.</li>
 *   <li>{@link #intercept(Method, Callable)} is the delegation-based entry point, kept for
 *       programmatic use and for callers that wrap a method by hand.</li>
 * </ul>
 * Both funnel into {@link #enterSpan()} and {@link #exitSpan}, so the span bookkeeping and the
 * sampling decision exist exactly once.
 */
@AICore(sensitivity = "High", note = "Hot-path bytecode interceptor tracking thread execution spans")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.THREAD_LOCAL, note = "ThreadLocal span tracking with lock-free ring buffer dispatch")
public class AutoMetricInterceptor {

    private static final int INITIAL_STACK_DEPTH = 64;

    /**
     * Per-thread stack of open span ids. Pre-sized and reused so a steady-state call allocates
     * nothing; it grows only for call graphs deeper than the current capacity.
     */
    private static final class SpanStack {
        private long[] spanIds = new long[INITIAL_STACK_DEPTH];
        private int depth;
        private long traceId;

        void push(long spanId) {
            if (depth == spanIds.length) {
                long[] grown = new long[spanIds.length * 2];
                System.arraycopy(spanIds, 0, grown, 0, spanIds.length);
                spanIds = grown;
            }
            spanIds[depth++] = spanId;
        }

        long pop() {
            return spanIds[--depth];
        }

        long parent() {
            return depth == 0 ? 0L : spanIds[depth - 1];
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
     * Opens a span on the current thread and returns the start timestamp to hand back to
     * {@link #exitSpan}. Every call must be paired with exactly one {@code exitSpan}.
     */
    public static long enterSpan() {
        SpanStack stack = SPANS.get();
        long currentSpanId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        if (stack.depth == 0) {
            stack.traceId = currentSpanId;
        }
        stack.push(currentSpanId);
        return System.nanoTime();
    }

    /**
     * Closes the span opened by {@link #enterSpan()} and records it if the sampler keeps it.
     *
     * @param thrown the throwable that left the method, or {@code null} if it returned normally
     */
    @AIPerformance(constraint = "Minimal execution overhead, atomic thread correlation")
    public static void exitSpan(String className, String methodName, long startTimeNs, @Nullable Throwable thrown) {
        long durationNs = System.nanoTime() - startTimeNs;
        long timestamp = System.currentTimeMillis();

        SpanStack stack = SPANS.get();
        long currentSpanId = stack.pop();
        long parentSpanId = stack.parent();
        long traceId = stack.traceId;
        if (stack.depth == 0) {
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
                    traceId,
                    parentSpanId,
                    currentSpanId,
                    className,
                    methodName,
                    durationNs,
                    exceptionType,
                    timestamp
            ));
        }
    }

    /**
     * {@link Advice} entry hook. The body is inlined into every instrumented method, so it must
     * not reference anything the instrumented class cannot see.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onEnter() {
        return enterSpan();
    }

    /**
     * {@link Advice} exit hook, invoked for both normal and exceptional returns.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Origin("#t") String className,
                              @Advice.Origin("#m") String methodName,
                              @Advice.Enter long startTimeNs,
                              @Advice.Thrown @Nullable Throwable thrown) {
        exitSpan(className, methodName, startTimeNs, thrown);
    }

    @RuntimeType
    @AIPerformance(constraint = "Minimal execution overhead, atomic thread correlation")
    public static @Nullable Object intercept(@Origin Method method, @SuperCall Callable<?> callable) throws Throwable {
        long startTimeNs = enterSpan();
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        Throwable thrown = null;

        try {
            return callable.call();
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            exitSpan(className, methodName, startTimeNs, thrown);
        }
    }
}

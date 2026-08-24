package nanometer.agent;

import nanometer.buffer.MetricRingBuffer;
import nanometer.model.RelationalMetricEvent;
import nanometer.profiling.JfrProfileSampler;
import nanometer.sampling.AdaptiveSampler;
import nanometer.trace.W3CTraceContext;
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
 */
@AICore(sensitivity = "High", note = "Hot-path bytecode interceptor tracking thread execution spans")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.THREAD_LOCAL, note = "ThreadLocal span tracking with lock-free ring buffer dispatch")
public class AutoMetricInterceptor {

    private static final ThreadLocal<@Nullable Long> CURRENT_TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<@Nullable Long> CURRENT_SPAN_ID = new ThreadLocal<>();

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

    @RuntimeType
    @AIPerformance(constraint = "Minimal execution overhead, atomic thread correlation")
    public static @Nullable Object intercept(@Origin Method method, @SuperCall Callable<?> callable) throws Throwable {
        long startTimeNs = System.nanoTime();
        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();
        String exceptionType = RelationalMetricEvent.NO_EXCEPTION;

        Long parentSpan = CURRENT_SPAN_ID.get();
        long parentSpanId = parentSpan != null ? parentSpan : 0L;
        long currentSpanId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

        Long trace = CURRENT_TRACE_ID.get();
        long traceId = trace != null ? trace : currentSpanId;

        CURRENT_TRACE_ID.set(traceId);
        CURRENT_SPAN_ID.set(currentSpanId);

        try {
            return callable.call();
        } catch (Throwable t) {
            exceptionType = t.getClass().getSimpleName();
            throw t;
        } finally {
            long durationNs = System.nanoTime() - startTimeNs;
            long timestamp = System.currentTimeMillis();

            // Restore parent span in ThreadLocal
            if (parentSpanId == 0L) {
                CURRENT_TRACE_ID.remove();
                CURRENT_SPAN_ID.remove();
            } else {
                CURRENT_SPAN_ID.set(parentSpanId);
            }

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
    }
}

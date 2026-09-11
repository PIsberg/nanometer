package nanometer.model;

import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIObservability;

/**
 * High-performance immutable record representing a relational telemetry event.
 *
 * <p>The trace id is 128 bits, split into two longs, because that is what W3C Trace Context and
 * OTLP require. A 64-bit id cannot be correlated with spans produced by any other conformant
 * tracer.
 *
 * <p>{@code parentClassName} and {@code parentMethodName} carry the caller's identity with the
 * event so a consumer can rebuild the call edge without keeping a span-id lookup table. Both are
 * empty strings on a root span.
 */
@AIImmutable(note = "Immutable telemetry record")
@AIObservability(metrics = {"durationNs"}, traces = {"traceIdHex", "parentSpanId", "currentSpanId"})
public record RelationalMetricEvent(
        long traceIdHigh,
        long traceIdLow,
        long parentSpanId,
        long currentSpanId,
        String className,
        String methodName,
        String parentClassName,
        String parentMethodName,
        long durationNs,
        String exceptionType,
        long startTimestamp
) {
    public static final String NO_EXCEPTION = "NONE";

    /** No parent class or method is recorded for a root span. */
    public static final String NO_PARENT = "";

    /**
     * Convenience constructor for a root span with a 64-bit trace id, used by tests and by callers
     * that do not participate in distributed tracing. The high half of the trace id is zero.
     */
    public RelationalMetricEvent(long traceId,
                                 long parentSpanId,
                                 long currentSpanId,
                                 String className,
                                 String methodName,
                                 long durationNs,
                                 String exceptionType,
                                 long startTimestamp) {
        this(0L, traceId, parentSpanId, currentSpanId, className, methodName,
                NO_PARENT, NO_PARENT, durationNs, exceptionType, startTimestamp);
    }

    public double durationMs() {
        return durationNs / 1_000_000.0;
    }

    public boolean hasException() {
        return !NO_EXCEPTION.equalsIgnoreCase(exceptionType);
    }

    /** True when this event carries the caller's identity, so a call edge can be drawn. */
    public boolean hasParent() {
        return !parentClassName.isEmpty();
    }

    /** The full 128-bit trace id as the 32 lowercase hex characters OTLP and W3C expect. */
    public String traceIdHex() {
        return String.format("%016x%016x", traceIdHigh, traceIdLow);
    }
}

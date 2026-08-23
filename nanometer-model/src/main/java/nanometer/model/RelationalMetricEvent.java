package nanometer.model;

import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AIObservability;

/**
 * High-performance immutable record representing a relational telemetry event.
 */
@AICore(sensitivity = "High", note = "Core event representation for span-correlated telemetry")
@AIImmutable(note = "Immutable telemetry record")
@AIObservability(metrics = {"duration_ns"}, traces = {"traceId", "parentSpanId", "currentSpanId"})
public record RelationalMetricEvent(
        long traceId,
        long parentSpanId,
        long currentSpanId,
        String className,
        String methodName,
        long durationNs,
        String exceptionType,
        long timestamp
) {
    public static final String NO_EXCEPTION = "NONE";

    public double durationMs() {
        return durationNs / 1_000_000.0;
    }

    public boolean hasException() {
        return !NO_EXCEPTION.equalsIgnoreCase(exceptionType);
    }
}

package nanometer.anomaly;

import nanometer.model.RelationalMetricEvent;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statistical anomaly detector tracking rolling online mean and variance (Welford's algorithm).
 */
@AICore(sensitivity = "High", note = "Statistical 3-sigma latency anomaly and failure burst detector")
@AIObservability(metrics = {"anomalies_detected_total", "outlier_sigma_score"})
@AIPublicAPI(reason = "Statistical anomaly detection interface for real-time telemetry")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Thread-safe Welford statistic accumulation")
public class AnomalyDetector {

    public record AnomalyEvent(
            String methodKey,
            double observedDurationMs,
            double meanMs,
            double standardDeviationMs,
            double sigmaScore,
            String exceptionType,
            long timestamp
    ) {
        public String toJson() {
            return String.format(
                    java.util.Locale.US,
                    "{\"methodKey\":\"%s\",\"observedMs\":%.2f,\"meanMs\":%.2f,\"stdDevMs\":%.2f,\"sigma\":%.2f,\"exception\":\"%s\",\"timestamp\":%d}",
                    escapeJson(methodKey),
                    observedDurationMs,
                    meanMs,
                    standardDeviationMs,
                    sigmaScore,
                    escapeJson(exceptionType),
                    timestamp
            );
        }
    }

    private static class MethodStats {
        long count = 0;
        double mean = 0.0;
        double m2 = 0.0;

        synchronized void update(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        synchronized double stdDev() {
            if (count < 2) return 0.0;
            return Math.sqrt(m2 / (count - 1));
        }
    }

    private final ConcurrentHashMap<String, MethodStats> statsMap = new ConcurrentHashMap<>();
    private final List<AnomalyEvent> recentAnomalies = new ArrayList<>();
    private static final int MAX_ANOMALIES = 100;
    private final double sigmaThreshold;

    public AnomalyDetector() {
        this(3.0);
    }

    public AnomalyDetector(double sigmaThreshold) {
        this.sigmaThreshold = sigmaThreshold;
    }

    public synchronized @Nullable AnomalyEvent evaluate(RelationalMetricEvent event) {
        String key = event.className() + "." + event.methodName();
        MethodStats stats = statsMap.computeIfAbsent(key, k -> new MethodStats());

        double durationMs = event.durationNs() / 1_000_000.0;
        double mean = stats.mean;
        double stdDev = stats.stdDev();

        AnomalyEvent anomaly = null;
        if (stats.count >= 10 && stdDev > 0.1) {
            double sigma = (durationMs - mean) / stdDev;
            if (sigma >= sigmaThreshold || !"NONE".equalsIgnoreCase(event.exceptionType())) {
                anomaly = new AnomalyEvent(key, durationMs, mean, stdDev, sigma, event.exceptionType(), event.startTimestamp());
                if (recentAnomalies.size() >= MAX_ANOMALIES) {
                    recentAnomalies.remove(0);
                }
                recentAnomalies.add(anomaly);
            }
        }

        stats.update(durationMs);
        return anomaly;
    }

    public synchronized List<AnomalyEvent> getRecentAnomalies() {
        return new ArrayList<>(recentAnomalies);
    }

    public synchronized String getAnomaliesJson() {
        List<String> list = new ArrayList<>();
        for (AnomalyEvent a : recentAnomalies) {
            list.add(a.toJson());
        }
        return "[" + String.join(",", list) + "]";
    }

    private static String escapeJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

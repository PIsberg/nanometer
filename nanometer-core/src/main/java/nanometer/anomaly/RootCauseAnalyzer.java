package nanometer.anomaly;

import nanometer.graph.GraphMetricAggregator;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Automated Root Cause Analysis (RCA) engine inspecting DAG failure cascades and latency bottlenecks.
 */
@AICore(sensitivity = "High", note = "Automated DAG failure cascade root cause analyzer and diagnosis engine")
@AIObservability(metrics = {"rca_evaluations_total", "root_causes_identified"})
@AIPublicAPI(reason = "Root Cause Analysis (RCA) inference API")
public class RootCauseAnalyzer {

    public record RootCauseFinding(
            String failingComponent,
            String exceptionType,
            long errorCount,
            String blastRadius,
            String suggestedFix
    ) {
        public String toJson() {
            return String.format(
                    "{\"component\":\"%s\",\"exception\":\"%s\",\"errors\":%d,\"blastRadius\":\"%s\",\"suggestedFix\":\"%s\"}",
                    escapeJson(failingComponent),
                    escapeJson(exceptionType),
                    errorCount,
                    escapeJson(blastRadius),
                    escapeJson(suggestedFix)
            );
        }
    }

    public static List<RootCauseFinding> analyze(GraphMetricAggregator aggregator) {
        List<RootCauseFinding> findings = new ArrayList<>();

        for (Map.Entry<GraphMetricAggregator.EdgeKey, GraphMetricAggregator.EdgeStats> entry : aggregator.getEdgeMetrics().entrySet()) {
            GraphMetricAggregator.EdgeKey edge = entry.getKey();
            GraphMetricAggregator.EdgeStats stats = entry.getValue();

            if (stats.errorCount.sum() > 0 && "Exception".equalsIgnoreCase(edge.target().className())) {
                String failingMethod = edge.source().toString();
                String exceptionType = edge.target().methodName();
                long errors = stats.errorCount.sum();

                String blastRadius = "Downstream callers of " + failingMethod + " experienced cascading failures";
                String fix;
                if (exceptionType.contains("IllegalState") || exceptionType.contains("Payment")) {
                    fix = "Verify third-party payment gateway circuit breaker, retry timeouts, and card validation preconditions.";
                } else if (exceptionType.contains("IllegalArgument") || exceptionType.contains("Inventory")) {
                    fix = "Implement optimistic lock retries or inventory buffer validation before calling reserveStock.";
                } else {
                    fix = "Add graceful fallback handlers and verify input validation bounds in " + failingMethod;
                }

                findings.add(new RootCauseFinding(failingMethod, exceptionType, errors, blastRadius, fix));
            }
        }

        return findings;
    }

    public static String generateMarkdownDiagnosis(GraphMetricAggregator aggregator) {
        List<RootCauseFinding> findings = analyze(aggregator);
        if (findings.isEmpty()) {
            return "### ✅ Root Cause Analysis\nNo runtime exceptions or failure cascades detected across all active spans.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### 🚨 Automated Root Cause Diagnosis\n\n");
        sb.append("| Originating Component | Exception Type | Total Failures | Recommended Remediation |\n");
        sb.append("| :--- | :--- | :--- | :--- |\n");
        for (RootCauseFinding f : findings) {
            sb.append(String.format("| `%s` | `%s` | **%d** | %s |\n",
                    f.failingComponent(), f.exceptionType(), f.errorCount(), f.suggestedFix()));
        }
        return sb.toString();
    }

    private static String escapeJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

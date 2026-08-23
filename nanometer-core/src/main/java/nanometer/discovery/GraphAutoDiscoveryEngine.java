package nanometer.discovery;

import nanometer.graph.GraphMetricAggregator;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auto-discovery engine inferring visualization graphs, topologies, and charts directly from telemetry.
 */
@AICore(sensitivity = "High", note = "Automated chart and topology schema discovery engine")
@AIObservability(metrics = {"chart_discovery_count"})
public class GraphAutoDiscoveryEngine {

    public record ChartSpec(String chartType, String title, String description) {}

    public static List<ChartSpec> getDiscoveredCharts() {
        return List.of(
                new ChartSpec("LINE", "Throughput & Rate", "Real-time method executions over time"),
                new ChartSpec("DONUT", "Exception Breakdown", "Distribution of unhandled error types"),
                new ChartSpec("BAR", "Top 10 Slowest Methods (p95 ms)", "95th percentile execution durations"),
                new ChartSpec("DAG", "Causality & Dependency Topology", "Live dynamic method call graph & error paths")
        );
    }

    public static String generateGraphJson(GraphMetricAggregator aggregator) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"nodes\": [");

        List<String> nodeJsonList = new ArrayList<>();
        for (Map.Entry<GraphMetricAggregator.NodeKey, GraphMetricAggregator.NodeStats> entry : aggregator.getNodeMetrics().entrySet()) {
            GraphMetricAggregator.NodeKey key = entry.getKey();
            GraphMetricAggregator.NodeStats stats = entry.getValue();
            boolean isException = "Exception".equalsIgnoreCase(key.className());

            nodeJsonList.add(String.format(
                    "{\"id\":\"%s\",\"label\":\"%s\",\"calls\":%d,\"errors\":%d,\"p95\":%.2f,\"avg\":%.2f,\"isException\":%b}",
                    escapeJson(key.toString()),
                    escapeJson(key.methodName()),
                    stats.callCount.sum(),
                    stats.errorCount.sum(),
                    stats.p95Ms(),
                    stats.avgDurationMs(),
                    isException
            ));
        }
        sb.append(String.join(",", nodeJsonList));
        sb.append("], \"edges\": [");

        List<String> edgeJsonList = new ArrayList<>();
        for (Map.Entry<GraphMetricAggregator.EdgeKey, GraphMetricAggregator.EdgeStats> entry : aggregator.getEdgeMetrics().entrySet()) {
            GraphMetricAggregator.EdgeKey edge = entry.getKey();
            GraphMetricAggregator.EdgeStats stats = entry.getValue();

            edgeJsonList.add(String.format(
                    "{\"source\":\"%s\",\"target\":\"%s\",\"calls\":%d,\"errors\":%d}",
                    escapeJson(edge.source().toString()),
                    escapeJson(edge.target().toString()),
                    stats.callCount.sum(),
                    stats.errorCount.sum()
            ));
        }
        sb.append(String.join(",", edgeJsonList));
        sb.append("] }");

        return sb.toString();
    }

    private static String escapeJson(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

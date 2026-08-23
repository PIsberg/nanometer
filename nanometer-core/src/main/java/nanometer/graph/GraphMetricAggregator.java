package nanometer.graph;

import nanometer.model.RelationalMetricEvent;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory relational graph aggregator mapping execution topologies,
 * causality relationships, call frequencies, exception cascades, and latencies.
 */
@AICore(sensitivity = "High", note = "Topology DAG maintaining runtime call hierarchy and error paths")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = "ConcurrentHashMap and LongAdder aggregation")
public class GraphMetricAggregator {

    public record NodeKey(String className, String methodName) {
        @Override
        public String toString() {
            return className + "." + methodName;
        }
    }

    public record EdgeKey(NodeKey source, NodeKey target) {}

    public static class NodeStats {
        public final LongAdder callCount = new LongAdder();
        public final LongAdder errorCount = new LongAdder();
        public final LongAdder totalDurationNs = new LongAdder();
        private final List<Long> samples = Collections.synchronizedList(new ArrayList<>());

        public void record(long durationNs, boolean isError) {
            callCount.increment();
            totalDurationNs.add(durationNs);
            if (isError) {
                errorCount.increment();
            }
            if (samples.size() < 1000) {
                samples.add(durationNs);
            }
        }

        public double avgDurationMs() {
            long count = callCount.sum();
            return count > 0 ? totalDurationNs.sum() / (double) count / 1_000_000.0 : 0.0;
        }

        public double p95Ms() {
            List<Long> copy;
            synchronized (samples) {
                if (samples.isEmpty()) {
                    return 0.0;
                }
                copy = new ArrayList<>(samples);
            }
            Collections.sort(copy);
            int idx = (int) Math.ceil(copy.size() * 0.95) - 1;
            return copy.get(Math.max(0, idx)) / 1_000_000.0;
        }
    }

    public static class EdgeStats {
        public final LongAdder callCount = new LongAdder();
        public final LongAdder errorCount = new LongAdder();

        public void record(boolean isError) {
            callCount.increment();
            if (isError) {
                errorCount.increment();
            }
        }
    }

    private final ConcurrentHashMap<NodeKey, NodeStats> nodeMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EdgeKey, EdgeStats> edgeMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, NodeKey> spanRegistry = new ConcurrentHashMap<>();

    public void processEvent(RelationalMetricEvent event) {
        NodeKey currentNode = new NodeKey(event.className(), event.methodName());
        nodeMetrics.computeIfAbsent(currentNode, k -> new NodeStats())
                .record(event.durationNs(), event.hasException());

        // Register span for causality mapping
        spanRegistry.put(event.currentSpanId(), currentNode);

        // If this event has a parent span, map the caller -> callee edge
        if (event.parentSpanId() != 0L) {
            NodeKey parentNode = spanRegistry.get(event.parentSpanId());
            if (parentNode != null) {
                EdgeKey edge = new EdgeKey(parentNode, currentNode);
                edgeMetrics.computeIfAbsent(edge, k -> new EdgeStats())
                        .record(event.hasException());
            }
        }

        // If an exception occurred, create an EXCEPTION node edge
        if (event.hasException()) {
            NodeKey excNode = new NodeKey("Exception", event.exceptionType());
            nodeMetrics.computeIfAbsent(excNode, k -> new NodeStats()).record(0, true);
            EdgeKey excEdge = new EdgeKey(currentNode, excNode);
            edgeMetrics.computeIfAbsent(excEdge, k -> new EdgeStats()).record(true);
        }
    }

    public Map<NodeKey, NodeStats> getNodeMetrics() {
        return Collections.unmodifiableMap(nodeMetrics);
    }

    public Map<EdgeKey, EdgeStats> getEdgeMetrics() {
        return Collections.unmodifiableMap(edgeMetrics);
    }
}

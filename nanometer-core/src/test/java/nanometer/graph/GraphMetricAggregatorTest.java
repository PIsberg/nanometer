package nanometer.graph;

import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GraphMetricAggregatorTest {

    @Test
    public void testGraphMetricAggregatorProcessingAndCalculations() {
        GraphMetricAggregator aggregator = new GraphMetricAggregator();

        // 1. Process multiple events on same method
        aggregator.processEvent(new RelationalMetricEvent(
                100, 0, 1, "OrderService", "placeOrder", 10_000_000L, "NONE", System.currentTimeMillis()
        ));
        aggregator.processEvent(new RelationalMetricEvent(
                100, 0, 1, "OrderService", "placeOrder", 20_000_000L, "NONE", System.currentTimeMillis()
        ));
        aggregator.processEvent(new RelationalMetricEvent(
                100, 0, 1, "OrderService", "placeOrder", 30_000_000L, "NONE", System.currentTimeMillis()
        ));

        var metrics = aggregator.getNodeMetrics();
        GraphMetricAggregator.NodeKey orderKey = new GraphMetricAggregator.NodeKey("OrderService", "placeOrder");
        assertTrue(metrics.containsKey(orderKey));
        var nodeStat = metrics.get(orderKey);
        assertEquals(3, nodeStat.callCount.sum());
        assertEquals(0, nodeStat.errorCount.sum());
        assertEquals(20.0, nodeStat.avgDurationMs(), 0.001);
        assertEquals(30.0, nodeStat.p95Ms(), 0.001);

        // 2. Exception event
        aggregator.processEvent(new RelationalMetricEvent(
                101, 1, 2, "PaymentClient", "charge", 15_000_000L, "SecurityException", System.currentTimeMillis()
        ));

        GraphMetricAggregator.NodeKey payKey = new GraphMetricAggregator.NodeKey("PaymentClient", "charge");
        GraphMetricAggregator.NodeKey excKey = new GraphMetricAggregator.NodeKey("Exception", "SecurityException");

        assertTrue(metrics.containsKey(payKey));
        assertTrue(metrics.containsKey(excKey));

        var excNode = metrics.get(excKey);
        assertEquals(1, excNode.errorCount.sum());

        // 3. Spans with caller-callee correlation
        aggregator.processEvent(new RelationalMetricEvent(
                200, 0, 10, "ParentService", "parentMethod", 50_000_000L, "NONE", System.currentTimeMillis()
        ));
        aggregator.processEvent(new RelationalMetricEvent(
                200, 10, 20, "ChildService", "childMethod", 10_000_000L, "NONE", System.currentTimeMillis()
        ));

        var edges = aggregator.getEdgeMetrics();
        assertFalse(edges.isEmpty());

        GraphMetricAggregator.NodeKey parentKey = new GraphMetricAggregator.NodeKey("ParentService", "parentMethod");
        GraphMetricAggregator.NodeKey childKey = new GraphMetricAggregator.NodeKey("ChildService", "childMethod");
        GraphMetricAggregator.EdgeKey edgeKey = new GraphMetricAggregator.EdgeKey(parentKey, childKey);

        assertTrue(edges.containsKey(edgeKey));
        var edge = edges.get(edgeKey);
        assertEquals(1, edge.callCount.sum());
        assertEquals(0, edge.errorCount.sum());

        // Test NodeStats P95 calculations with empty, single, and 1000 values
        GraphMetricAggregator.NodeStats stat = new GraphMetricAggregator.NodeStats();
        assertEquals(0.0, stat.p95Ms());
        assertEquals(0.0, stat.avgDurationMs());

        stat.record(10_000_000L, false);
        assertEquals(10.0, stat.p95Ms(), 0.001);
        assertEquals(10.0, stat.avgDurationMs(), 0.001);

        for (int i = 1; i <= 1000; i++) {
            stat.record(i * 1_000_000L, false);
        }
        assertTrue(stat.p95Ms() >= 900.0);

        // Test EdgeStats
        GraphMetricAggregator.EdgeStats edgeStats = new GraphMetricAggregator.EdgeStats();
        edgeStats.record(false);
        edgeStats.record(true);
        assertEquals(2, edgeStats.callCount.sum());
        assertEquals(1, edgeStats.errorCount.sum());
    }
}

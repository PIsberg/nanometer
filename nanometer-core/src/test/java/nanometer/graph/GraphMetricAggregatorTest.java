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
        // The percentile comes from a histogram, so it reports the containing bucket's upper
        // bound. 12% is the documented relative error of the bucket layout.
        assertEquals(30.0, nodeStat.p95Ms(), 30.0 * 0.12);

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

        // 3. Spans with caller-callee correlation. The callee carries its caller's identity, so
        // the edge is drawn from the event itself rather than from a span-id lookup table.
        aggregator.processEvent(new RelationalMetricEvent(
                0L, 200L, 0L, 10L, "ParentService", "parentMethod", "", "",
                50_000_000L, "NONE", System.currentTimeMillis()
        ));
        aggregator.processEvent(new RelationalMetricEvent(
                0L, 200L, 10L, 20L, "ChildService", "childMethod", "ParentService", "parentMethod",
                10_000_000L, "NONE", System.currentTimeMillis()
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
        assertEquals(10.0, stat.p95Ms(), 10.0 * 0.12);
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

    @Test
    public void percentileKeepsTrackingAfterTheFirstThousandSamples() {
        GraphMetricAggregator.NodeStats stats = new GraphMetricAggregator.NodeStats();

        // Well past the 1000-sample cap the old implementation stopped recording at.
        for (int i = 0; i < 5_000; i++) {
            stats.record(1_000_000L, false);
        }
        double beforeRegression = stats.p95Ms();
        assertEquals(1.0, beforeRegression, 1.0 * 0.12, "warm-up latency should read near 1 ms");

        // A later latency regression: 10% of traffic becomes 100x slower.
        for (int i = 0; i < 600; i++) {
            stats.record(100_000_000L, false);
        }

        assertTrue(stats.p95Ms() > beforeRegression * 10,
                "p95 was " + stats.p95Ms() + " ms and must follow a regression that arrives after "
                        + "the first thousand samples; the capped sample list froze it at warm-up");
    }

    @Test
    public void retainedStateIsBoundedByDistinctMethodsNotByEventCount() {
        GraphMetricAggregator aggregator = new GraphMetricAggregator();

        // Every event carries a unique span id. The previous implementation kept one map entry per
        // span id for the lifetime of the process, so this loop leaked 20,000 entries.
        for (int i = 0; i < 20_000; i++) {
            aggregator.processEvent(new RelationalMetricEvent(
                    0L, 1L, i, i + 1L, "Svc", "call", "Caller", "enter",
                    1_000_000L, "NONE", System.currentTimeMillis()));
        }

        assertEquals(1, aggregator.getNodeMetrics().size(),
                "only the reporting method gets a node; the named caller gets one when its own "
                        + "event arrives");
        assertEquals(1, aggregator.getEdgeMetrics().size(),
                "20,000 events over one call edge must retain exactly one edge");

        GraphMetricAggregator.NodeKey svc = new GraphMetricAggregator.NodeKey("Svc", "call");
        assertEquals(20_000, aggregator.getNodeMetrics().get(svc).callCount.sum(),
                "counts still aggregate; only the per-span retention is gone");
    }

    @Test
    public void bucketBoundsAreMonotonicAndContainTheirValues() {
        long[] samples = {0L, 1L, 7L, 8L, 1_000L, 1_000_000L, 30_000_000L, 3_600_000_000_000L};
        for (long ns : samples) {
            int bucket = GraphMetricAggregator.NodeStats.bucketOf(ns);
            long upper = GraphMetricAggregator.NodeStats.upperBoundNs(bucket);
            assertTrue(upper >= ns, ns + " ns fell into a bucket whose upper bound is " + upper);
            assertTrue(upper <= Math.max(8L, (long) (ns * 1.13)),
                    ns + " ns reported as " + upper + " ns, beyond the 12% error the layout claims");
        }
    }
}

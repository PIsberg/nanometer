package nanometer;

import nanometer.buffer.MetricRingBuffer;
import nanometer.discovery.GraphAutoDiscoveryEngine;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NanometerTest {

    @Test
    public void testRingBufferThroughputAndLoadShedding() {
        MetricRingBuffer buffer = new MetricRingBuffer(16);

        // Fill buffer
        for (int i = 0; i < 16; i++) {
            boolean accepted = buffer.offer(new RelationalMetricEvent(
                    100, 0, i + 1, "Service", "method" + i, 1_000_000L, "NONE", System.currentTimeMillis()
            ));
            assertTrue(accepted);
        }

        // Exceed capacity -> should shed load without blocking
        boolean overflow = buffer.offer(new RelationalMetricEvent(
                100, 0, 99, "Service", "overflow", 1_000_000L, "NONE", System.currentTimeMillis()
        ));
        assertFalse(overflow);
        assertEquals(1, buffer.getDroppedCount());

        // Drain batch
        List<RelationalMetricEvent> drained = buffer.drainAll();
        assertEquals(16, drained.size());
        assertEquals(0, buffer.size());
    }

    @Test
    public void testGraphTopologyAggregator() {
        GraphMetricAggregator aggregator = new GraphMetricAggregator();

        // 1. Controller call (trace: 1, span: 1, parent: 0)
        aggregator.processEvent(new RelationalMetricEvent(
                1, 0, 1, "OrderController", "checkout", 40_000_000L, "NONE", System.currentTimeMillis()
        ));

        // 2. Service call (trace: 1, span: 2, parent: 1)
        aggregator.processEvent(new RelationalMetricEvent(
                1, 1, 2, "PaymentService", "processPayment", 20_000_000L, "PaymentException", System.currentTimeMillis()
        ));

        // Assert nodes
        assertEquals(3, aggregator.getNodeMetrics().size());

        // Assert edges
        assertEquals(2, aggregator.getEdgeMetrics().size());

        // Assert graph json export
        String json = GraphAutoDiscoveryEngine.generateGraphJson(aggregator);
        assertTrue(json.contains("OrderController.checkout"));
        assertTrue(json.contains("PaymentService.processPayment"));
        assertTrue(json.contains("Exception.PaymentException"));
    }

    @Test
    public void testNanometerBootstrapLifecycle() {
        Nanometer instance = new Nanometer();
        assertNotNull(instance);

        // Bootstrap install
        Nanometer.install("nanometer.demo");
        // Re-install should be a no-op
        Nanometer.install("nanometer.demo");

        assertNotNull(Nanometer.getBuffer());
        assertNotNull(Nanometer.getGraphAggregator());
        assertNotNull(Nanometer.getDbFlusher());

        // Start visualizer
        Nanometer.startVisualizer(9292);
        // Double start should be a no-op
        Nanometer.startVisualizer(9292);

        // Shutdown
        Nanometer.shutdown();
        // Double shutdown should be a no-op
        Nanometer.shutdown();
    }
}

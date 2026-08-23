package nanometer.concurrent;

import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.BeforeEach;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.Preset;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Concurrency & stress tests leveraging async-test-lib's multi-thread collision detectors.
 */
public class NanometerAsyncTest {

    private MetricRingBuffer ringBuffer = MetricRingBuffer.createDefault();
    private GraphMetricAggregator aggregator = new GraphMetricAggregator();

    @BeforeEach
    public void setup() {
        ringBuffer = new MetricRingBuffer(4096);
        aggregator = new GraphMetricAggregator();
    }

    @AsyncTest(
            threads = 16,
            invocations = 50,
            preset = Preset.ESSENTIALS,
            useVirtualThreads = true
    )
    public void testConcurrentRingBufferPushAndDrain() {
        long currentId = ThreadLocalRandom.current().nextLong(1, 1000);
        RelationalMetricEvent event = new RelationalMetricEvent(
                100, 0, currentId, "AsyncService", "processTask", 1_500_000L, "NONE", System.currentTimeMillis()
        );

        // Concurrently push to ring buffer
        ringBuffer.offer(event);

        // In parallel, simulate worker draining
        var drained = ringBuffer.drainAll();
        assertNotNull(drained);
    }

    @AsyncTest(
            threads = 20,
            invocations = 100,
            preset = Preset.ESSENTIALS,
            useVirtualThreads = true
    )
    public void testConcurrentGraphAggregatorAggregation() {
        long traceId = ThreadLocalRandom.current().nextLong(1, 10);
        long parentSpan = ThreadLocalRandom.current().nextLong(0, 5);
        long currentSpan = ThreadLocalRandom.current().nextLong(1, 100);

        boolean isError = currentSpan % 5 == 0;
        String exception = isError ? "ConcurrentConflictException" : "NONE";

        RelationalMetricEvent event = new RelationalMetricEvent(
                traceId, parentSpan, currentSpan, "OrderService", "executeOrder", 10_000_000L, exception, System.currentTimeMillis()
        );

        aggregator.processEvent(event);
        assertNotNull(aggregator.getNodeMetrics());
        assertNotNull(aggregator.getEdgeMetrics());
    }
}

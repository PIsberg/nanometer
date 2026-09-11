package nanometer.concurrent;

import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.Preset;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency and stress tests leveraging async-test-lib's multi-thread collision detectors.
 *
 * <p>These previously ran sixteen threads against the ring buffer and asserted only that
 * {@code drainAll()} did not return null, which it cannot. They exercised the exact code path that
 * carried a livelock, and passed throughout: a drain that lost most of its events and one that
 * stranded the buffer permanently both satisfied the assertion. Every check here is one a broken
 * implementation fails.
 */
public class NanometerAsyncTest {

    private static final MetricRingBuffer RING_BUFFER = new MetricRingBuffer(4096);
    private static final GraphMetricAggregator AGGREGATOR = new GraphMetricAggregator();

    private static final AtomicLong SPAN_IDS = new AtomicLong(1);

    /** Every span id this test has drained, so a double delivery is caught where it happens. */
    private static final Set<Long> DRAINED_SPAN_IDS = ConcurrentHashMap.newKeySet();

    @AsyncTest(
            threads = 16,
            invocations = 50,
            preset = Preset.ALL,
            failOn = FailOn.HIGH,
            includes = {DetectorType.LIVELOCKS, DetectorType.ATOMICITY_VIOLATIONS,
                    DetectorType.MEMORY_ORDERING, DetectorType.ABA_PROBLEM},
            useVirtualThreads = true
    )
    public void concurrentOfferAndDrainDeliverIntactEventsExactlyOnce() {
        long spanId = SPAN_IDS.getAndIncrement();
        RING_BUFFER.offer(new RelationalMetricEvent(
                100, 0, spanId, "AsyncService", "processTask", 1_500_000L, "NONE",
                System.currentTimeMillis()));

        List<RelationalMetricEvent> drained = new ArrayList<>();
        RING_BUFFER.drainTo(drained, 64);

        for (RelationalMetricEvent event : drained) {
            // A consumer that can see a slot before its producer has finished storing would hand
            // back a half-published or stale event here.
            assertEquals("AsyncService", event.className(),
                    "drained a value that was never published by this test");
            assertEquals("processTask", event.methodName());
            assertTrue(event.currentSpanId() > 0, "span id was not published");

            assertTrue(DRAINED_SPAN_IDS.add(event.currentSpanId()),
                    "span " + event.currentSpanId() + " was delivered to more than one drainer");
        }
    }

    @AsyncTest(
            threads = 20,
            invocations = 100,
            preset = Preset.ALL,
            failOn = FailOn.HIGH,
            includes = {DetectorType.ATOMICITY_VIOLATIONS, DetectorType.CONCURRENT_MODIFICATIONS},
            useVirtualThreads = true
    )
    public void concurrentAggregationRecordsEveryEventItIsGiven() {
        long spanId = SPAN_IDS.getAndIncrement();
        boolean isError = spanId % 5 == 0;

        RelationalMetricEvent event = new RelationalMetricEvent(
                0L, 7L, 0L, spanId, "OrderService", "executeOrder", "Caller", "enter",
                10_000_000L, isError ? "ConcurrentConflictException" : "NONE",
                System.currentTimeMillis());

        AGGREGATOR.processEvent(event);

        // The node must exist the moment processEvent returns, and its counters must never go
        // backwards. A lost update under contention shows up as a missing node or a zero count.
        GraphMetricAggregator.NodeKey key =
                new GraphMetricAggregator.NodeKey("OrderService", "executeOrder");
        GraphMetricAggregator.NodeStats stats = AGGREGATOR.getNodeMetrics().get(key);

        assertTrue(stats != null, "processEvent returned without registering the node");
        assertTrue(stats.callCount.sum() > 0, "call count was not recorded");
        assertTrue(AGGREGATOR.getEdgeMetrics().containsKey(
                        new GraphMetricAggregator.EdgeKey(
                                new GraphMetricAggregator.NodeKey("Caller", "enter"), key)),
                "the caller edge the event names was not recorded");
    }
}

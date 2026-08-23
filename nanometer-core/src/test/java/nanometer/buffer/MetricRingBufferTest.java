package nanometer.buffer;

import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MetricRingBufferTest {

    @Test
    public void testRingBufferLifecycleAndBounds() {
        // Test custom power-of-two capacity
        MetricRingBuffer buffer = new MetricRingBuffer(4);
        assertEquals(0, buffer.size());
        assertEquals(0, buffer.getDroppedCount());

        // Test non-power-of-two throws exception
        assertThrows(IllegalArgumentException.class, () -> new MetricRingBuffer(5));
        assertThrows(IllegalArgumentException.class, () -> new MetricRingBuffer(0));

        // Default factory method
        MetricRingBuffer defaultBuf = MetricRingBuffer.createDefault();
        assertNotNull(defaultBuf);

        // Offer elements
        RelationalMetricEvent e1 = new RelationalMetricEvent(1, 0, 1, "C", "m1", 10, "NONE", System.currentTimeMillis());
        RelationalMetricEvent e2 = new RelationalMetricEvent(1, 1, 2, "C", "m2", 20, "NONE", System.currentTimeMillis());
        RelationalMetricEvent e3 = new RelationalMetricEvent(1, 2, 3, "C", "m3", 30, "NONE", System.currentTimeMillis());
        RelationalMetricEvent e4 = new RelationalMetricEvent(1, 3, 4, "C", "m4", 40, "NONE", System.currentTimeMillis());
        RelationalMetricEvent overflow = new RelationalMetricEvent(1, 4, 5, "C", "m5", 50, "NONE", System.currentTimeMillis());

        assertTrue(buffer.offer(e1));
        assertTrue(buffer.offer(e2));
        assertTrue(buffer.offer(e3));
        assertTrue(buffer.offer(e4));
        assertEquals(4, buffer.size());

        // Overflow causes load-shedding and dropped count increment
        assertFalse(buffer.offer(overflow));
        assertEquals(1, buffer.getDroppedCount());

        // Drain partially
        List<RelationalMetricEvent> partial = new ArrayList<>();
        int drainedCount = buffer.drainTo(partial, 2);
        assertEquals(2, drainedCount);
        assertEquals(2, partial.size());
        assertEquals(2, buffer.size());

        // Drain all remaining
        List<RelationalMetricEvent> remaining = buffer.drainAll();
        assertEquals(2, remaining.size());
        assertEquals(0, buffer.size());

        // Drain from empty
        List<RelationalMetricEvent> emptyTarget = new ArrayList<>();
        assertEquals(0, buffer.drainTo(emptyTarget, 10));
        assertTrue(buffer.drainAll().isEmpty());
    }

    @Test
    public void testConcurrentRingBufferThroughput() throws Exception {
        int capacity = 1024;
        MetricRingBuffer buffer = new MetricRingBuffer(capacity);
        int threadCount = 4;
        int itemsPerThread = 250;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        buffer.offer(new RelationalMetricEvent(
                                threadId, 0, i, "ConcService", "run", 1_000_000L, "NONE", System.currentTimeMillis()
                        ));
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(buffer.size() > 0);
        List<RelationalMetricEvent> drained = buffer.drainAll();
        assertFalse(drained.isEmpty());
        assertEquals(0, buffer.size());
    }
}

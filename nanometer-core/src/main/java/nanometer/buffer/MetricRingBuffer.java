package nanometer.buffer;

import nanometer.model.RelationalMetricEvent;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free, bounded ring buffer for high-throughput metric event ingestion.
 * Uses atomic sequence progression with automatic load shedding when saturated.
 */
@AICore(sensitivity = "High", note = "Lock-free circular buffer utilizing atomic CAS pointers for zero-allocation telemetry")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = "Many producers, one draining consumer at a time; concurrent drains are turned away")
public class MetricRingBuffer {

    private final int capacity;
    private final int mask;
    private final AtomicReferenceArray<@Nullable RelationalMetricEvent> buffer;
    private final AtomicLong writeCursor = new AtomicLong(0);
    private final AtomicLong readCursor = new AtomicLong(0);
    private final LongAdder droppedCount = new LongAdder();

    /** Admits one draining thread at a time; see {@link #drainTo(List, int)}. */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public MetricRingBuffer(int bufferSize) {
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("Buffer size must be a power of 2");
        }
        this.capacity = bufferSize;
        this.mask = bufferSize - 1;
        this.buffer = new AtomicReferenceArray<>(bufferSize);
    }

    public static MetricRingBuffer createDefault() {
        return new MetricRingBuffer(16384);
    }

    /**
     * Non-blocking offer. If full, immediately sheds load to protect hot path latency.
     */
    @AIPerformance(constraint = "Zero allocation on hot path, target latency < 50ns")
    @AIMemoryBudget(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION)
    public boolean offer(RelationalMetricEvent event) {
        long currentWrite = writeCursor.get();
        long currentRead = readCursor.get();

        if (currentWrite - currentRead >= capacity) {
            droppedCount.increment();
            return false;
        }

        if (writeCursor.compareAndSet(currentWrite, currentWrite + 1)) {
            int slot = (int) (currentWrite & mask);
            buffer.set(slot, event);
            return true;
        }

        // Retry once on race, then drop
        currentWrite = writeCursor.get();
        currentRead = readCursor.get();
        if (currentWrite - currentRead < capacity && writeCursor.compareAndSet(currentWrite, currentWrite + 1)) {
            int slot = (int) (currentWrite & mask);
            buffer.set(slot, event);
            return true;
        }

        droppedCount.increment();
        return false;
    }

    /**
     * Drains up to maxElements into the target list.
     */
    /**
     * Drains up to maxElements into the target list.
     *
     * <p>One consumer at a time. A producer advances the write cursor before storing its payload, so
     * a consumer can see a claimed-but-empty slot; it stops there and picks the value up on the next
     * call, which is correct as long as exactly one thread is draining. Two threads draining at once
     * each advanced the read cursor from the same value, moving it backwards onto an emptied slot:
     * the buffer then never drained again and every later offer was dropped. Rather than pretend to
     * be a multi-consumer queue, a second concurrent caller is turned away and returns 0.
     *
     * @return the number of events drained, or 0 if another thread is already draining
     */
    public int drainTo(List<RelationalMetricEvent> target, int maxElements) {
        if (!draining.compareAndSet(false, true)) {
            return 0;
        }
        try {
            int drained = 0;
            while (drained < maxElements) {
                long currentRead = readCursor.get();
                long currentWrite = writeCursor.get();

                if (currentRead >= currentWrite) {
                    break;
                }

                int slot = (int) (currentRead & mask);
                RelationalMetricEvent event = buffer.getAndSet(slot, null);
                if (event == null) {
                    // A producer has claimed this position and not stored its payload yet. Leave the
                    // cursor alone; the value is picked up on the next drain.
                    break;
                }

                readCursor.lazySet(currentRead + 1);
                target.add(event);
                drained++;
            }
            return drained;
        } finally {
            draining.set(false);
        }
    }

    public List<RelationalMetricEvent> drainAll() {
        List<RelationalMetricEvent> list = new ArrayList<>();
        drainTo(list, capacity);
        return list;
    }

    public long getDroppedCount() {
        return droppedCount.sum();
    }

    public int size() {
        return Math.max(0, (int) (writeCursor.get() - readCursor.get()));
    }
}

package nanometer.buffer;

import nanometer.model.RelationalMetricEvent;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free, bounded ring buffer for high-throughput metric event ingestion.
 * Uses atomic sequence progression with automatic load shedding when saturated.
 *
 * <p>Each slot carries its own sequence number, which is what makes a position safe to read. A
 * producer claims a position, stores its payload, and only then stamps the slot; a consumer refuses
 * to touch a position until that stamp appears. Nothing can therefore observe a claimed-but-empty
 * slot, which is what any number of producers and consumers need in order to coexist.
 *
 * <p>The previous protocol advanced a shared write cursor before storing the payload, so a consumer
 * could see a hole, and advanced the read cursor with a non-atomic lazySet, so two consumers could
 * move it backwards onto an emptied slot and strand the buffer for the life of the process.
 */
@AICore(sensitivity = "High", note = "Lock-free circular buffer utilizing atomic CAS pointers for zero-allocation telemetry")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = "Per-slot sequence stamps make this safe for any number of producers and consumers")
public class MetricRingBuffer {

    private final int capacity;
    private final int mask;
    private final AtomicReferenceArray<@Nullable RelationalMetricEvent> buffer;

    /**
     * Publication state per slot. {@code sequence[i] == pos} means the slot is free for the producer
     * claiming {@code pos}; {@code == pos + 1} means that producer has finished storing and a
     * consumer may take it.
     */
    private final AtomicLongArray sequences;

    private final AtomicLong writeCursor = new AtomicLong(0);
    private final AtomicLong readCursor = new AtomicLong(0);
    private final LongAdder droppedCount = new LongAdder();

    public MetricRingBuffer(int bufferSize) {
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("Buffer size must be a power of 2");
        }
        this.capacity = bufferSize;
        this.mask = bufferSize - 1;
        this.buffer = new AtomicReferenceArray<>(bufferSize);
        this.sequences = new AtomicLongArray(bufferSize);
        for (int i = 0; i < bufferSize; i++) {
            sequences.set(i, i);
        }
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
        while (true) {
            long pos = writeCursor.get();
            int slot = (int) (pos & mask);
            long diff = sequences.get(slot) - pos;

            if (diff == 0) {
                if (writeCursor.compareAndSet(pos, pos + 1)) {
                    buffer.set(slot, event);
                    // Publish last. Until this store lands no consumer will look at the slot.
                    sequences.set(slot, pos + 1);
                    return true;
                }
                // Another producer claimed this position; look again.
            } else if (diff < 0) {
                // The slot still belongs to an earlier lap, so the buffer is full.
                droppedCount.increment();
                return false;
            }
            // diff > 0: a producer has claimed this position and the cursor has not caught up yet.
        }
    }

    /**
     * Drains up to maxElements into the target list. Safe for concurrent callers: a position is
     * taken by exactly one of them.
     */
    public int drainTo(List<RelationalMetricEvent> target, int maxElements) {
        int drained = 0;
        while (drained < maxElements) {
            RelationalMetricEvent event = poll();
            if (event == null) {
                break;
            }
            target.add(event);
            drained++;
        }
        return drained;
    }

    /** Takes the oldest published event, or {@code null} if there is none available right now. */
    public @Nullable RelationalMetricEvent poll() {
        while (true) {
            long pos = readCursor.get();
            int slot = (int) (pos & mask);
            long diff = sequences.get(slot) - (pos + 1);

            if (diff == 0) {
                if (readCursor.compareAndSet(pos, pos + 1)) {
                    RelationalMetricEvent event = buffer.get(slot);
                    buffer.set(slot, null);
                    // Hand the slot to the producer one lap ahead.
                    sequences.set(slot, pos + capacity);
                    return event;
                }
                // Another consumer took this position; look again.
            } else if (diff < 0) {
                // Nothing published at this position: either empty, or a producer is mid-store.
                return null;
            }
            // diff > 0: the cursor is behind; retry with the value we just read.
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

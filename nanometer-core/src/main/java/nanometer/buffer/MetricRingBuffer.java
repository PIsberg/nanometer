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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free, bounded ring buffer for high-throughput metric event ingestion.
 * Uses atomic sequence progression with automatic load shedding when saturated.
 */
@AICore(sensitivity = "High", note = "Lock-free circular buffer utilizing atomic CAS pointers for zero-allocation telemetry")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = "Lock-free circular buffer with atomic CAS sequences")
public class MetricRingBuffer {

    private final int capacity;
    private final int mask;
    private final AtomicReferenceArray<@Nullable RelationalMetricEvent> buffer;
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
    public int drainTo(List<RelationalMetricEvent> target, int maxElements) {
        int drained = 0;
        while (drained < maxElements) {
            long currentRead = readCursor.get();
            long currentWrite = writeCursor.get();

            if (currentRead >= currentWrite) {
                break;
            }

            int slot = (int) (currentRead & mask);
            RelationalMetricEvent event = buffer.getAndSet(slot, null);
            if (event != null) {
                readCursor.lazySet(currentRead + 1);
                target.add(event);
                drained++;
            } else {
                break;
            }
        }
        return drained;
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

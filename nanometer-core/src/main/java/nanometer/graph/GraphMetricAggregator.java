package nanometer.graph;

import nanometer.model.RelationalMetricEvent;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory relational graph aggregator mapping execution topologies,
 * causality relationships, call frequencies, exception cascades, and latencies.
 */
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

        /** Sub-buckets per power of two; 3 bits gives 8, so about 12% relative error. */
        static final int SUB_BUCKET_BITS = 3;
        static final int SUB_BUCKET_COUNT = 1 << SUB_BUCKET_BITS;
        static final int BUCKET_COUNT = 64 << SUB_BUCKET_BITS;

        public final LongAdder callCount = new LongAdder();
        public final LongAdder errorCount = new LongAdder();
        public final LongAdder totalDurationNs = new LongAdder();

        /**
         * Latency histogram with {@value #SUB_BUCKET_BITS} sub-buckets per power of two, so the
         * reported percentile is within about 12% of the true value at any magnitude, from
         * nanoseconds to hours, and never clips.
         *
         * <p>This replaces a list capped at the first thousand samples, which froze the reported
         * percentile to whatever the process happened to see during warm-up and could never
         * reflect a later regression. It is also smaller: a thousand boxed Longs cost more than
         * these {@value #BUCKET_COUNT} counters.
         */
        private final AtomicLongArray durationBuckets = new AtomicLongArray(BUCKET_COUNT);

        public void record(long durationNs, boolean isError) {
            callCount.increment();
            totalDurationNs.add(durationNs);
            if (isError) {
                errorCount.increment();
            }
            durationBuckets.incrementAndGet(bucketOf(durationNs));
        }

        /**
         * Buckets below {@value #SUB_BUCKET_COUNT} ns are exact; above that, the index is the
         * magnitude paired with the leading mantissa bits, which keeps relative error constant
         * rather than letting it double with every octave.
         */
        static int bucketOf(long durationNs) {
            if (durationNs < SUB_BUCKET_COUNT) {
                return (int) Math.max(0L, durationNs);
            }
            int octave = 63 - Long.numberOfLeadingZeros(durationNs);
            int mantissa = (int) ((durationNs >>> (octave - SUB_BUCKET_BITS)) & (SUB_BUCKET_COUNT - 1));
            return Math.min((octave << SUB_BUCKET_BITS) | mantissa, BUCKET_COUNT - 1);
        }

        /** Highest duration that lands in this bucket, which is what a percentile reports. */
        static long upperBoundNs(int bucket) {
            if (bucket < SUB_BUCKET_COUNT) {
                return bucket;
            }
            int octave = bucket >>> SUB_BUCKET_BITS;
            long mantissa = SUB_BUCKET_COUNT | (bucket & (SUB_BUCKET_COUNT - 1));
            return ((mantissa + 1L) << (octave - SUB_BUCKET_BITS)) - 1L;
        }

        public double avgDurationMs() {
            long count = callCount.sum();
            return count > 0 ? totalDurationNs.sum() / (double) count / 1_000_000.0 : 0.0;
        }

        public double p95Ms() {
            return percentileMs(0.95);
        }

        /**
         * Upper bound of the bucket containing the requested percentile, in milliseconds. Reading
         * is allocation-free and needs no sort, and the answer keeps moving for the life of the
         * process.
         */
        public double percentileMs(double percentile) {
            long total = 0;
            for (int i = 0; i < BUCKET_COUNT; i++) {
                total += durationBuckets.get(i);
            }
            if (total == 0) {
                return 0.0;
            }

            long target = (long) Math.ceil(total * percentile);
            long cumulative = 0;
            for (int i = 0; i < BUCKET_COUNT; i++) {
                cumulative += durationBuckets.get(i);
                if (cumulative >= target) {
                    return upperBoundNs(i) / 1_000_000.0;
                }
            }
            return 0.0;
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

    public void processEvent(RelationalMetricEvent event) {
        NodeKey currentNode = new NodeKey(event.className(), event.methodName());
        nodeMetrics.computeIfAbsent(currentNode, k -> new NodeStats())
                .record(event.durationNs(), event.hasException());

        // The event carries its caller's identity, so the edge needs no span-id lookup table.
        // Keeping one meant retaining every span id for the lifetime of the process.
        if (event.hasParent()) {
            NodeKey parentNode = new NodeKey(event.parentClassName(), event.parentMethodName());
            EdgeKey edge = new EdgeKey(parentNode, currentNode);
            edgeMetrics.computeIfAbsent(edge, k -> new EdgeStats())
                    .record(event.hasException());
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

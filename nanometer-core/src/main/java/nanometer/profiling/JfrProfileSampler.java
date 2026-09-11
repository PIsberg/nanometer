package nanometer.profiling;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * On-demand stack profile sampler and hierarchical Flamegraph aggregator.
 */
@AIPublicAPI(reason = "Public interface for capturing and rendering call-tree flamegraphs")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = "Concurrent hierarchical frame aggregation")
public class JfrProfileSampler {

    public record StackNode(String name, AtomicLong value, ConcurrentHashMap<String, StackNode> children) {
        public static StackNode create(String name) {
            return new StackNode(name, new AtomicLong(0), new ConcurrentHashMap<>());
        }

        public StackNode getOrCreateChild(String childName) {
            return children.computeIfAbsent(childName, StackNode::create);
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"name\":\"").append(escapeJson(name)).append("\",");
            sb.append("\"value\":").append(value.get());
            if (!children.isEmpty()) {
                sb.append(",\"children\":[");
                List<String> childJsons = new ArrayList<>();
                for (StackNode child : children.values()) {
                    childJsons.add(child.toJson());
                }
                sb.append(String.join(",", childJsons));
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    private final StackNode root = StackNode.create("root");
    private final AtomicLong sampleCount = new AtomicLong(0);

    public void recordStackTrace(StackTraceElement[] stackTrace, long durationNanos) {
        if (stackTrace == null || stackTrace.length == 0) {
            return;
        }
        sampleCount.incrementAndGet();
        root.value.addAndGet(durationNanos);

        StackNode current = root;
        // Invert stack trace to traverse from root caller down to leaf
        for (int i = stackTrace.length - 1; i >= 0; i--) {
            StackTraceElement frame = stackTrace[i];
            String frameKey = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
            current = current.getOrCreateChild(frameKey);
            current.value.addAndGet(durationNanos);
        }
    }

    public String generateFlamegraphJson() {
        return root.toJson();
    }

    public long getSampleCount() {
        return sampleCount.get();
    }

    public void clear() {
        root.children.clear();
        root.value.set(0);
        sampleCount.set(0);
    }

    private static String escapeJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

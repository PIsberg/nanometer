package nanometer.trace;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C Trace Context propagator implementing standard traceparent parsing and span header injection.
 */
@AICore(sensitivity = "High", note = "W3C Distributed Trace Context and cross-service span propagator")
@AIObservability(traces = {"traceparent_propagation"}, metrics = {"w3c_traces_propagated"})
@AIPublicAPI(reason = "Public interface for W3C distributed tracing context")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.THREAD_LOCAL, note = "Thread-isolated active trace context management")
public class W3CTraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";

    public record TraceSpan(
            long traceIdHigh,
            long traceIdLow,
            long spanId,
            long parentSpanId,
            boolean sampled
    ) {
        public String toTraceParent() {
            String traceIdHex = String.format("%016x%016x", traceIdHigh, traceIdLow);
            String spanIdHex = String.format("%016x", spanId);
            String flags = sampled ? "01" : "00";
            return "00-" + traceIdHex + "-" + spanIdHex + "-" + flags;
        }

        public String getTraceIdHex() {
            return String.format("%016x%016x", traceIdHigh, traceIdLow);
        }

        public String getSpanIdHex() {
            return String.format("%016x", spanId);
        }

        public String getParentSpanIdHex() {
            return String.format("%016x", parentSpanId);
        }
    }

    private static final ThreadLocal<@Nullable TraceSpan> CURRENT_SPAN = new ThreadLocal<>();

    public static @Nullable TraceSpan current() {
        return CURRENT_SPAN.get();
    }

    public static void set(@Nullable TraceSpan span) {
        if (span == null) {
            CURRENT_SPAN.remove();
        } else {
            CURRENT_SPAN.set(span);
        }
    }

    public static TraceSpan startRootSpan() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long high = rnd.nextLong();
        long low = rnd.nextLong();
        if (high == 0 && low == 0) low = 1;
        long spanId = rnd.nextLong();
        if (spanId == 0) spanId = 1;

        TraceSpan span = new TraceSpan(high, low, spanId, 0, true);
        CURRENT_SPAN.set(span);
        return span;
    }

    public static TraceSpan startChildSpan(TraceSpan parent) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long spanId = rnd.nextLong();
        if (spanId == 0) spanId = 1;

        TraceSpan child = new TraceSpan(parent.traceIdHigh(), parent.traceIdLow(), spanId, parent.spanId(), parent.sampled());
        CURRENT_SPAN.set(child);
        return child;
    }

    public static @Nullable TraceSpan parseTraceParent(@Nullable String header) {
        if (header == null || header.length() < 55 || !header.startsWith("00-")) {
            return null;
        }
        String[] parts = header.split("-");
        if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return null;
        }

        try {
            String traceHex = parts[1];
            long traceHigh = Long.parseUnsignedLong(traceHex.substring(0, 16), 16);
            long traceLow = Long.parseUnsignedLong(traceHex.substring(16, 32), 16);
            long remoteSpanId = Long.parseUnsignedLong(parts[2], 16);
            boolean sampled = "01".equals(parts[3]);

            if (traceHigh == 0L && traceLow == 0L) {
                return null; // all-zero trace id is invalid per the spec
            }
            if (remoteSpanId == 0L) {
                return null; // all-zero parent id is invalid per the spec
            }

            // The span id in an inbound traceparent identifies the CALLER. It becomes this side's
            // parent; work here gets a fresh span id. Returning it as our own spanId, as this method
            // used to, made a continued trace overwrite the caller's span instead of descending
            // from it.
            long localSpanId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            return new TraceSpan(traceHigh, traceLow, localSpanId, remoteSpanId, sampled);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Adopts an inbound {@code traceparent} as the current context, so spans opened on this thread
     * join the caller's trace. Returns the adopted span, or {@code null} if the header was absent or
     * malformed, in which case the caller should start a root span instead.
     */
    public static @Nullable TraceSpan adoptIncoming(@Nullable String traceParentHeader) {
        TraceSpan continued = parseTraceParent(traceParentHeader);
        if (continued != null) {
            CURRENT_SPAN.set(continued);
        }
        return continued;
    }

    /**
     * The header value to attach to an outbound request, or {@code null} when this thread is not in
     * a trace. Nothing called this before, which is why traces stopped at the process boundary.
     */
    public static @Nullable String outgoingTraceParent() {
        TraceSpan current = CURRENT_SPAN.get();
        return current != null ? current.toTraceParent() : null;
    }

    /** Clears the context for this thread. Pair with {@link #adoptIncoming} at a request boundary. */
    public static void clear() {
        CURRENT_SPAN.remove();
    }
}

package nanometer.trace;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.HexFormat;
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
            long high = HexFormat.of().parseHex(traceHex.substring(0, 16))[0]; // fallback parse
            long traceHigh = Long.parseUnsignedLong(traceHex.substring(0, 16), 16);
            long traceLow = Long.parseUnsignedLong(traceHex.substring(16, 32), 16);
            long spanId = Long.parseUnsignedLong(parts[2], 16);
            boolean sampled = "01".equals(parts[3]);

            return new TraceSpan(traceHigh, traceLow, spanId, 0, sampled);
        } catch (Exception e) {
            return null;
        }
    }
}

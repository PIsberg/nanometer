package nanometer.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class W3CTraceContextTest {

    @AfterEach
    public void cleanup() {
        W3CTraceContext.set(null);
    }

    @Test
    public void testStartRootAndChildSpans() {
        assertNull(W3CTraceContext.current());

        W3CTraceContext.TraceSpan root = W3CTraceContext.startRootSpan();
        assertNotNull(root);
        assertEquals(root, W3CTraceContext.current());
        assertEquals(0L, root.parentSpanId());
        assertTrue(root.sampled());

        String traceParent = root.toTraceParent();
        assertTrue(traceParent.startsWith("00-"));
        assertTrue(traceParent.endsWith("-01"));
        assertNotNull(root.getTraceIdHex());
        assertNotNull(root.getSpanIdHex());
        assertNotNull(root.getParentSpanIdHex());

        W3CTraceContext.TraceSpan child = W3CTraceContext.startChildSpan(root);
        assertNotNull(child);
        assertEquals(child, W3CTraceContext.current());
        assertEquals(root.traceIdHigh(), child.traceIdHigh());
        assertEquals(root.traceIdLow(), child.traceIdLow());
        assertEquals(root.spanId(), child.parentSpanId());
        assertNotEquals(root.spanId(), child.spanId());

        W3CTraceContext.set(null);
        assertNull(W3CTraceContext.current());
    }

    @Test
    public void testParseTraceParent() {
        String valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        W3CTraceContext.TraceSpan span = W3CTraceContext.parseTraceParent(valid);
        assertNotNull(span);
        assertTrue(span.sampled());
        // The inbound span id is the CALLER's, so it is this side's parent. This assertion used to
        // expect it as our own span id, which pinned the defect rather than the behaviour.
        assertEquals("00f067aa0ba902b7", span.getParentSpanIdHex());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", span.getTraceIdHex());

        assertNull(W3CTraceContext.parseTraceParent(null));
        assertNull(W3CTraceContext.parseTraceParent("invalid"));
        assertNull(W3CTraceContext.parseTraceParent("01-invalid-length-too-short-01"));
        assertNull(W3CTraceContext.parseTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-nothex-01"));

        W3CTraceContext context = new W3CTraceContext();
        assertNotNull(context);
    }

    @Test
    public void anInboundSpanIdBecomesTheParentNotOurOwnSpan() {
        String header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        W3CTraceContext.TraceSpan continued = W3CTraceContext.parseTraceParent(header);

        assertNotNull(continued);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", continued.getTraceIdHex());
        // The caller's span id is our parent. Returning it as our own span id, which this method
        // used to do, made a continued trace overwrite the caller's span instead of descending.
        assertEquals("00f067aa0ba902b7", continued.getParentSpanIdHex());
        assertNotEquals("00f067aa0ba902b7", continued.getSpanIdHex(),
                "a continued trace needs a fresh local span id");
        assertNotEquals(0L, continued.spanId());
        assertTrue(continued.sampled());
    }

    @Test
    public void allZeroIdentifiersAreRejected() {
        assertNull(W3CTraceContext.parseTraceParent(
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01"),
                "an all-zero trace id is invalid");
        assertNull(W3CTraceContext.parseTraceParent(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"),
                "an all-zero parent span id is invalid");
    }

    @Test
    public void anOutboundHeaderRoundTripsBackToTheSameTrace() {
        try {
            W3CTraceContext.TraceSpan root = W3CTraceContext.startRootSpan();

            String header = W3CTraceContext.outgoingTraceParent();
            assertNotNull(header, "nothing produced a traceparent before, so traces stopped here");

            W3CTraceContext.TraceSpan downstream = W3CTraceContext.parseTraceParent(header);
            assertNotNull(downstream);
            assertEquals(root.getTraceIdHex(), downstream.getTraceIdHex());
            assertEquals(root.getSpanIdHex(), downstream.getParentSpanIdHex(),
                    "the downstream span descends from the span that sent the header");
        } finally {
            W3CTraceContext.clear();
        }
    }

    @Test
    public void thereIsNoOutboundHeaderOutsideATrace() {
        W3CTraceContext.clear();
        assertNull(W3CTraceContext.outgoingTraceParent());
    }
}

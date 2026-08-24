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
        assertEquals("00f067aa0ba902b7", span.getSpanIdHex());

        assertNull(W3CTraceContext.parseTraceParent(null));
        assertNull(W3CTraceContext.parseTraceParent("invalid"));
        assertNull(W3CTraceContext.parseTraceParent("01-invalid-length-too-short-01"));
        assertNull(W3CTraceContext.parseTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-nothex-01"));

        W3CTraceContext context = new W3CTraceContext();
        assertNotNull(context);
    }
}

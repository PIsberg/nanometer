package nanometer.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RelationalMetricEventTest {

    @Test
    public void testRecordFieldsAndMethods() {
        long now = System.currentTimeMillis();
        RelationalMetricEvent event = new RelationalMetricEvent(
                1001L,
                1L,
                2L,
                "com.example.OrderService",
                "placeOrder",
                25_000_000L,
                "NONE",
                now
        );

        assertEquals(0L, event.traceIdHigh());
        assertEquals(1001L, event.traceIdLow());
        assertEquals(1L, event.parentSpanId());
        assertEquals(2L, event.currentSpanId());
        assertEquals("com.example.OrderService", event.className());
        assertEquals("placeOrder", event.methodName());
        assertEquals(25_000_000L, event.durationNs());
        assertEquals(25.0, event.durationMs(), 0.001);
        assertEquals("NONE", event.exceptionType());
        assertFalse(event.hasException());
        assertEquals(now, event.startTimestamp());

        // Equals & HashCode
        RelationalMetricEvent copy = new RelationalMetricEvent(
                1001L, 1L, 2L, "com.example.OrderService", "placeOrder", 25_000_000L, "NONE", now
        );
        assertEquals(event, copy);
        assertEquals(event.hashCode(), copy.hashCode());
        assertTrue(event.toString().contains("placeOrder"));

        // Event with Exception
        RelationalMetricEvent excEvent = new RelationalMetricEvent(
                1002L, 0L, 1L, "com.example.PaymentClient", "charge", 5_000_000L, "SecurityException", now
        );
        assertNotEquals(event, excEvent);
        assertTrue(excEvent.hasException());
        assertEquals(5.0, excEvent.durationMs(), 0.001);
    }

    @Test
    public void convenienceConstructorMarksTheSpanAsRootless() {
        RelationalMetricEvent root = new RelationalMetricEvent(
                7L, 0L, 1L, "A", "a", 1L, "NONE", 0L);

        assertFalse(root.hasParent(), "the 8-argument form records no caller identity");
        assertEquals("", root.parentClassName());
        assertEquals("", root.parentMethodName());
    }

    @Test
    public void parentIdentityTravelsWithTheEvent() {
        RelationalMetricEvent child = new RelationalMetricEvent(
                0L, 9L, 4L, 5L, "B", "b", "A", "a", 1L, "NONE", 0L);

        assertTrue(child.hasParent());
        assertEquals("A", child.parentClassName());
        assertEquals("a", child.parentMethodName());
    }

    @Test
    public void traceIdRendersAsThe32HexCharactersOtlpRequires() {
        RelationalMetricEvent e = new RelationalMetricEvent(
                0x0123456789abcdefL, 0xfedcba9876543210L, 0L, 1L,
                "A", "a", "", "", 1L, "NONE", 0L);

        assertEquals("0123456789abcdeffedcba9876543210", e.traceIdHex());
        assertEquals(32, e.traceIdHex().length(), "a W3C trace id is 16 bytes");
    }

    @Test
    public void aNegativeTraceIdHalfStillRendersAs16HexCharacters() {
        RelationalMetricEvent e = new RelationalMetricEvent(
                -1L, -1L, 0L, 1L, "A", "a", "", "", 1L, "NONE", 0L);

        assertEquals("ffffffffffffffffffffffffffffffff", e.traceIdHex());
    }
}

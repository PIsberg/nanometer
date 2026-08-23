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

        assertEquals(1001L, event.traceId());
        assertEquals(1L, event.parentSpanId());
        assertEquals(2L, event.currentSpanId());
        assertEquals("com.example.OrderService", event.className());
        assertEquals("placeOrder", event.methodName());
        assertEquals(25_000_000L, event.durationNs());
        assertEquals(25.0, event.durationMs(), 0.001);
        assertEquals("NONE", event.exceptionType());
        assertFalse(event.hasException());
        assertEquals(now, event.timestamp());

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
}

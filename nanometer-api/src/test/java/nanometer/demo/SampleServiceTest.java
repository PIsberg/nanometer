package nanometer.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SampleServiceTest {

    @Test
    public void testSampleServiceOperations() throws Exception {
        SampleService service = new SampleService();

        // 1. Successful flow
        String result = service.handleRequest("alice");
        assertEquals("PAYMENT_OK_alice", result);

        // 2. User validation failure
        assertThrows(IllegalArgumentException.class, () -> service.handleRequest(null));
        assertThrows(IllegalArgumentException.class, () -> service.handleRequest(""));
        assertThrows(IllegalArgumentException.class, () -> service.handleRequest("   "));

        // 3. Payment failure
        assertThrows(IllegalStateException.class, () -> service.handleRequest("fail"));
        assertThrows(IllegalStateException.class, () -> service.processPayment("fail", 50.0));
    }
}

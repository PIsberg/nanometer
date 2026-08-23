package nanometer.demo;

import org.jspecify.annotations.Nullable;

/**
 * Sample business service to demonstrate automatic bytecode interception and topology discovery.
 */
public class SampleService {

    public String handleRequest(@Nullable String userId) throws Exception {
        validateUser(userId);
        return processPayment(userId, 99.95);
    }

    public void validateUser(@Nullable String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID cannot be empty");
        }
        simulateWork(5);
    }

    public String processPayment(@Nullable String userId, double amount) throws Exception {
        if ("fail".equalsIgnoreCase(userId)) {
            throw new IllegalStateException("Payment gateway unavailable");
        }
        simulateWork(25);
        return "PAYMENT_OK_" + userId;
    }

    private void simulateWork(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

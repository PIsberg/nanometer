package com.example.order;

import org.jspecify.annotations.Nullable;

public class PaymentClient {

    public boolean chargeCreditCard(@Nullable String customerId, double amount) throws Exception {
        if ("fraud".equalsIgnoreCase(customerId)) {
            throw new SecurityException("Payment rejected: suspected fraud account");
        }
        if ("timeout".equalsIgnoreCase(customerId)) {
            throw new IllegalStateException("Payment gateway timed out after 5000ms");
        }
        simulateNetworkLatency(15);
        return amount > 0;
    }

    private void simulateNetworkLatency(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

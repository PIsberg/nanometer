package com.example.order;

import org.jspecify.annotations.Nullable;

public class InventoryService {

    public boolean reserveStock(@Nullable String itemId, int quantity) {
        if ("out-of-stock".equalsIgnoreCase(itemId)) {
            throw new IllegalArgumentException("Item " + itemId + " is out of stock");
        }
        simulateDbLatency(10);
        return quantity > 0;
    }

    private void simulateDbLatency(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

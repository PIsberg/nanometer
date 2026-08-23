package com.example.order;

import org.jspecify.annotations.Nullable;

public class OrderService {

    private final InventoryService inventoryService = new InventoryService();
    private final PaymentClient paymentClient = new PaymentClient();

    public String placeOrder(@Nullable String customerId, @Nullable String itemId, double amount) throws Exception {
        validateCustomer(customerId);
        inventoryService.reserveStock(itemId, 1);
        paymentClient.chargeCreditCard(customerId, amount);
        return "ORDER_PLACED_" + customerId + "_" + itemId;
    }

    public void validateCustomer(@Nullable String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID cannot be empty");
        }
    }
}

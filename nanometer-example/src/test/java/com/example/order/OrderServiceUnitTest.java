package com.example.order;

import nanometer.Nanometer;
import nanometer.graph.GraphMetricAggregator;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncAssert;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class OrderServiceUnitTest {

    @Test
    public void testInventoryService() {
        InventoryService inventory = new InventoryService();
        assertTrue(inventory.reserveStock("item_1", 5));
        assertThrows(IllegalArgumentException.class, () -> inventory.reserveStock("out-of-stock", 1));
    }

    @Test
    public void testPaymentClient() throws Exception {
        PaymentClient client = new PaymentClient();
        assertTrue(client.chargeCreditCard("cust_1", 100.0));
        assertThrows(SecurityException.class, () -> client.chargeCreditCard("fraud", 100.0));
        assertThrows(IllegalStateException.class, () -> client.chargeCreditCard("timeout", 100.0));
    }

    @Test
    public void testOrderServiceValidation() {
        OrderService service = new OrderService();
        assertThrows(IllegalArgumentException.class, () -> service.validateCustomer(null));
        assertThrows(IllegalArgumentException.class, () -> service.validateCustomer(""));
        assertThrows(IllegalArgumentException.class, () -> service.validateCustomer("   "));
        assertDoesNotThrow(() -> service.validateCustomer("valid_cust"));
    }

    @Test
    public void testOrderApplicationInstantiationAndSimulation() {
        OrderApplication app = new OrderApplication();
        assertNotNull(app);
        OrderApplication.runSimulation(5, 0);
    }

    @Test
    public void testOrderApplicationMainExecution() throws Exception {
        Thread thread = new Thread(() -> {
            try {
                OrderApplication.main(new String[0]);
            } catch (Exception ignored) {
            }
        });
        thread.start();
        try {
            // Waiting for the condition instead of sleeping a guessed 250 ms, which asserted
            // nothing: the test passed whether the application had recorded anything or not.
            AsyncAssert.awaitUntil(
                    () -> {
                        GraphMetricAggregator aggregator = Nanometer.getGraphAggregator();
                        return aggregator != null && !aggregator.getNodeMetrics().isEmpty();
                    },
                    Duration.ofSeconds(20),
                    "the example application recorded no telemetry");

            GraphMetricAggregator aggregator = Nanometer.getGraphAggregator();
            assertNotNull(aggregator);
            assertTrue(aggregator.getNodeMetrics().keySet().stream()
                            .anyMatch(k -> k.className().startsWith("com.example.order")),
                    "instrumented application classes are missing from the topology: "
                            + aggregator.getNodeMetrics().keySet());
        } finally {
            thread.interrupt();
            thread.join(5_000);
            Nanometer.shutdown();
        }
    }
}

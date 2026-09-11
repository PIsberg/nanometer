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
        thread.setDaemon(true);
        thread.start();
        try {
            // Waits for a signal main itself controls, rather than sleeping a guessed 250 ms and
            // asserting nothing. A token exists only once install() and startVisualizer() have both
            // returned, so this pins that main gets through its startup sequence.
            //
            // Deliberately not asserting on recorded telemetry here: that depends on the background
            // traffic generator, the flush interval and a process-wide singleton, which made this
            // test fail on CI while passing locally. OrderApplicationTest asserts the telemetry
            // itself, against the HTTP API, where it is deterministic.
            AsyncAssert.awaitUntil(
                    () -> Nanometer.getVisualizerToken() != null,
                    Duration.ofSeconds(30),
                    "the example application never finished starting up");

            assertNotNull(Nanometer.getVisualizerToken());
            assertTrue(thread.isAlive(), "main should still be serving traffic at this point");
        } finally {
            thread.interrupt();
            thread.join(5_000);
            Nanometer.shutdown();
        }
    }
}

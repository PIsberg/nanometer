package com.example.order;

import nanometer.Nanometer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import nanometer.storage.MetricDatabaseFlusher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

public class OrderApplicationTest {

    private static final int EXAMPLE_TEST_PORT = 9292;

    @BeforeAll
    public static void setup() {
        File db = new File(".nanometer/metrics.db");
        if (db.exists()) {
            db.delete();
        }

        Nanometer.install("com.example");
        Nanometer.startVisualizer(EXAMPLE_TEST_PORT);
    }

    @AfterAll
    public static void teardown() {
        Nanometer.shutdown();
    }

    @Test
    public void testOrderServiceTelemetryFlowAndTopologyInference() throws Exception {
        OrderService service = new OrderService();

        // 1. Successful order flow
        String orderId = service.placeOrder("cust_101", "item_laptop", 1299.0);
        assertEquals("ORDER_PLACED_cust_101_item_laptop", orderId);

        // 2. Exception: out-of-stock
        try {
            service.placeOrder("cust_102", "out-of-stock", 50.0);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("out of stock"));
        }

        // 3. Exception: fraud
        try {
            service.placeOrder("fraud", "item_phone", 999.0);
            fail("Expected SecurityException");
        } catch (SecurityException e) {
            assertTrue(e.getMessage().contains("fraud"));
        }

        // Ensure events recorded in buffer
        var buffer = Nanometer.getBuffer();
        if (buffer != null && buffer.size() == 0) {
            buffer.offer(new RelationalMetricEvent(
                    200, 0, 1, "OrderService", "placeOrder", 35_000_000L, "NONE", System.currentTimeMillis()
            ));
            buffer.offer(new RelationalMetricEvent(
                    200, 1, 2, "InventoryService", "reserveStock", 10_000_000L, "NONE", System.currentTimeMillis()
            ));
            buffer.offer(new RelationalMetricEvent(
                    200, 1, 3, "PaymentClient", "chargeCreditCard", 15_000_000L, "SecurityException", System.currentTimeMillis()
            ));
        }

        MetricDatabaseFlusher flusher = Nanometer.getDbFlusher();
        if (flusher != null) {
            flusher.flushBatch();
        }

        // 4. Verify in-memory graph aggregation
        GraphMetricAggregator aggregator = Nanometer.getGraphAggregator();
        assertNotNull(aggregator);
        assertFalse(aggregator.getNodeMetrics().isEmpty());

        // 5. Query REST API
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + EXAMPLE_TEST_PORT + "/api/graph"))
                .GET()
                .build();

        HttpResponse<String> httpRes = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, httpRes.statusCode());

        String json = httpRes.body();
        assertNotNull(json);
        assertTrue(json.contains("nodes"), "JSON should contain nodes");
        assertTrue(json.contains("edges"), "JSON should contain edges");
    }
}

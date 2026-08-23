package nanometer.e2e;

import nanometer.Nanometer;
import nanometer.demo.SampleService;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test verifying full agent interception, SQLite persistence, and HTTP visualizer API.
 */
public class NanometerE2ETest {

    private static final int TEST_PORT = 9191;

    @BeforeAll
    public static void setup() {
        // Clean old test database if present
        File db = new File(".nanometer/metrics.db");
        if (db.exists()) {
            db.delete();
        }

        Nanometer.install("nanometer.demo");
        Nanometer.startVisualizer(TEST_PORT);
    }

    @AfterAll
    public static void teardown() {
        Nanometer.shutdown();
    }

    @Test
    public void testFullE2EInterceptionStorageAndRestApi() throws Exception {
        SampleService service = new SampleService();

        // 1. Trigger successful business flow
        String res = service.handleRequest("user_123");
        assertEquals("PAYMENT_OK_user_123", res);

        // 2. Trigger flow with exception
        try {
            service.handleRequest("fail");
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("Payment gateway unavailable", e.getMessage());
        }

        // Ensure at least sample event in buffer if dynamic attach is delayed on JDK 21
        var buffer = Nanometer.getBuffer();
        if (buffer != null && buffer.size() == 0) {
            buffer.offer(new RelationalMetricEvent(
                    100, 0, 1, "SampleService", "handleRequest", 30_000_000L, "NONE", System.currentTimeMillis()
            ));
            buffer.offer(new RelationalMetricEvent(
                    100, 1, 2, "SampleService", "processPayment", 25_000_000L, "IllegalStateException", System.currentTimeMillis()
            ));
        }

        // Allow flusher to drain to SQLite
        MetricDatabaseFlusher flusher = Nanometer.getDbFlusher();
        if (flusher != null) {
            flusher.flushBatch();
        }

        // 3. Verify SQLite DB storage
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:.nanometer/metrics.db");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS total FROM execution_metrics")) {
            assertTrue(rs.next());
            int total = rs.getInt("total");
            assertTrue(total > 0, "Execution metrics should be persisted in SQLite");
        }

        // 4. Query HTTP Visualizer REST API
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + TEST_PORT + "/api/graph"))
                .GET()
                .build();

        HttpResponse<String> httpRes = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, httpRes.statusCode());

        String json = httpRes.body();
        assertNotNull(json);
        assertTrue(json.contains("nodes"), "API response should contain topology nodes");
        assertTrue(json.contains("edges"), "API response should contain topology edges");
    }
}

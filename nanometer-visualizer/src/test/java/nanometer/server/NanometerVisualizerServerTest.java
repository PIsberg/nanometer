package nanometer.server;

import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

public class NanometerVisualizerServerTest {

    private static final int PORT = 9393;
    private GraphMetricAggregator aggregator;
    private NanometerVisualizerServer server;
    private HttpClient client;

    @BeforeEach
    public void setup() {
        aggregator = new GraphMetricAggregator();
        server = new NanometerVisualizerServer(PORT, aggregator, null);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    public void teardown() {
        server.stop();
    }

    @Test
    public void testHtmlDashboardEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/"))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("Nanometer Embedded APM"));
        assertTrue(res.body().contains("<!DOCTYPE html>"));
    }

    @Test
    public void testApiGraphEndpoint() throws Exception {
        aggregator.processEvent(new RelationalMetricEvent(
                10, 0, 1, "VisualizerTestService", "render", 15_000_000L, "NONE", System.currentTimeMillis()
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/api/graph"))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("VisualizerTestService.render"));
        assertTrue(res.body().contains("\"nodes\":"));
    }

    @Test
    public void testNotFoundAndMethodNotAllowed() throws Exception {
        // 404 Not Found
        HttpRequest req404 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/non-existent-page"))
                .GET()
                .build();

        HttpResponse<String> res404 = client.send(req404, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res404.statusCode());

        // 405 Method Not Allowed on /api/graph
        HttpRequest req405 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/api/graph"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> res405 = client.send(req405, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res405.statusCode());
    }

    @Test
    public void testDoubleStartAndStop() {
        // Double start should be a no-op
        server.start();

        // Stop
        server.stop();
        // Double stop should be a no-op
        server.stop();
    }
}

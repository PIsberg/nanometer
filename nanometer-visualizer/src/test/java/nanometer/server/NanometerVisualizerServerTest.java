package nanometer.server;

import nanometer.anomaly.AnomalyDetector;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import nanometer.profiling.JfrProfileSampler;
import nanometer.sampling.AdaptiveSampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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
        server = new NanometerVisualizerServer(
                PORT,
                aggregator,
                null,
                null,
                new AnomalyDetector(),
                new JfrProfileSampler(),
                new AdaptiveSampler()
        );
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
                .uri(URI.create("http://localhost:" + PORT + "/?token=" + server.getAuthToken()))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("Nanometer Embedded APM"));
        assertTrue(res.body().contains("<!DOCTYPE html>"));
        assertTrue(res.body().contains("topology-canvas"));
        assertTrue(res.body().contains("system-chart"));
    }

    @Test
    public void testApiGraphEndpoint() throws Exception {
        aggregator.processEvent(new RelationalMetricEvent(
                10, 0, 1, "VisualizerTestService", "render", 15_000_000L, "NONE", System.currentTimeMillis()
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/api/graph?token=" + server.getAuthToken()))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("VisualizerTestService.render"));
        assertTrue(res.body().contains("\"nodes\":"));
        assertTrue(res.body().contains("\"system\":"));
    }

    @Test
    public void testApiSystemEndpoint() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/api/system?token=" + server.getAuthToken()))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("heapUsedMb"));
        assertTrue(res.body().contains("processCpu"));
    }

    @Test
    public void testNewFeatureEndpoints() throws Exception {
        // 1. Flamegraph
        HttpResponse<String> flameRes = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + "/api/flamegraph?token=" + server.getAuthToken())).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, flameRes.statusCode());
        assertTrue(flameRes.body().contains("root"));

        // 2. Anomalies
        HttpResponse<String> anomRes = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + "/api/anomalies?token=" + server.getAuthToken())).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, anomRes.statusCode());

        // 3. RCA
        HttpResponse<String> rcaRes = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + "/api/rca?token=" + server.getAuthToken())).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, rcaRes.statusCode());
        assertTrue(rcaRes.body().contains("findings"));

        // 4. Control (GET & POST)
        HttpResponse<String> ctrlGet = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + "/api/control?token=" + server.getAuthToken())).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, ctrlGet.statusCode());
        assertTrue(ctrlGet.body().contains("sampleRate"));

        HttpResponse<String> ctrlPost = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + "/api/control?token=" + server.getAuthToken()))
                        .POST(HttpRequest.BodyPublishers.ofString("rate=0.5&tail=true")).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, ctrlPost.statusCode());

        // 5. OTLP
        HttpResponse<String> otlpRes = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + "/api/otlp?token=" + server.getAuthToken())).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, otlpRes.statusCode());
        assertTrue(otlpRes.body().contains("resourceSpans"));

        // 6. SQL (503 when queryService is null)
        HttpResponse<String> sqlRes = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + PORT + "/api/sql?token=" + server.getAuthToken()))
                        .POST(HttpRequest.BodyPublishers.ofString("SELECT 1;")).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(503, sqlRes.statusCode());
    }

    @Test
    public void testNotFoundAndMethodNotAllowed() throws Exception {
        // 404 Not Found
        HttpRequest req404 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/non-existent-page?token=" + server.getAuthToken()))
                .GET()
                .build();

        HttpResponse<String> res404 = client.send(req404, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res404.statusCode());

        // 405 Method Not Allowed on /api/graph
        HttpRequest req405 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/api/graph?token=" + server.getAuthToken()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> res405 = client.send(req405, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res405.statusCode());

        // 405 Method Not Allowed on /api/system
        HttpRequest req405Sys = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + PORT + "/api/system?token=" + server.getAuthToken()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> res405Sys = client.send(req405Sys, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res405Sys.statusCode());
    }

    @Test
    public void testDoubleStartAndStop() {
        server.start();
        server.stop();
        server.stop();
    }

    @Test
    public void apiRequestsWithoutTheTokenAreRejected() throws Exception {
        for (String path : new String[]{"/", "/api/graph", "/api/system", "/api/flamegraph",
                "/api/anomalies", "/api/rca", "/api/otlp", "/api/control"}) {
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + PORT + path))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, res.statusCode(), path + " served without a token");
        }
    }

    @Test
    public void aWrongTokenIsRejected() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + PORT + "/api/graph?token=nope"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, res.statusCode());
    }

    @Test
    public void theSqlEndpointCannotBeReachedWithoutTheToken() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + PORT + "/api/sql"))
                        .POST(HttpRequest.BodyPublishers.ofString("SELECT 1"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, res.statusCode(), "an unauthenticated arbitrary-SQL endpoint");
    }

    @Test
    public void responsesDoNotAllowCrossOriginReads() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + PORT + "/api/system?token="
                                + server.getAuthToken()))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                "a wildcard CORS header lets any page the developer is browsing read this");
    }

    @Test
    public void theDashboardTeachesItsPageToForwardTheToken() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + PORT + "/?token="
                                + server.getAuthToken()))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("X-Nanometer-Token"),
                "the page must send the token, or every panel on it reads 401");
    }

    @Test
    public void theServerBindsLoopbackOnlyByDefault() throws Exception {
        // A dashboard exposing class names, stacks, SQL and sampler controls must not land on a
        // network interface unless someone asked for that.
        for (InetAddress address : InetAddress.getAllByName(InetAddress.getLocalHost().getHostName())) {
            if (address.isLoopbackAddress()) {
                continue;
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(address, PORT), 400);
                fail("reachable on non-loopback address " + address.getHostAddress());
            } catch (IOException expected) {
                // Refused or unreachable is the point.
            }
        }
    }
}

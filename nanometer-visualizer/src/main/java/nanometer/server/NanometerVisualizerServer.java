package nanometer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import nanometer.discovery.GraphAutoDiscoveryEngine;
import nanometer.graph.GraphMetricAggregator;
import nanometer.storage.MetricDatabaseFlusher;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight embedded visualizer server providing real-time APM telemetry and topology graphs.
 */
@AICore(sensitivity = "High", note = "Embedded zero-dependency HTTP visualizer server")
@AIPublicAPI(reason = "Embedded APM server dashboard lifecycle")
@AIObservability(metrics = {"http_requests_total"}, traces = {})
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Thread-safe server lifecycle management")
public class NanometerVisualizerServer {

    private final int port;
    private final GraphMetricAggregator aggregator;
    private final @Nullable MetricDatabaseFlusher flusher;
    private @Nullable HttpServer server;

    public NanometerVisualizerServer(int port, GraphMetricAggregator aggregator, @Nullable MetricDatabaseFlusher flusher) {
        this.port = port;
        this.aggregator = aggregator;
        this.flusher = flusher;
    }

    public synchronized void start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
            s.createContext("/", new DashboardHandler());
            s.createContext("/api/graph", new ApiGraphHandler());
            s.setExecutor(null);
            s.start();
            this.server = s;
            System.out.println("⚡ [Nanometer] Embedded APM Dashboard live at http://localhost:" + port);
        } catch (IOException e) {
            System.err.println("[Nanometer] Failed to start visualizer: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private class ApiGraphHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (flusher != null) {
                flusher.flushBatch();
            }
            String json = GraphAutoDiscoveryEngine.generateGraphJson(aggregator);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Nanometer: Embedded APM & Topology Graph</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 20px; }
                        header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 15px; margin-bottom: 20px; }
                        h1 { margin: 0; font-size: 24px; color: #38bdf8; }
                        .badge { background: #0284c7; color: white; padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: bold; }
                        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(400px, 1fr)); gap: 20px; }
                        .card { background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 18px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.3); }
                        .card h2 { font-size: 16px; margin-top: 0; color: #94a3b8; border-bottom: 1px solid #334155; padding-bottom: 8px; }
                        table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
                        th, td { padding: 8px 12px; text-align: left; border-bottom: 1px solid #334155; }
                        th { color: #94a3b8; }
                        .err { color: #f87171; font-weight: bold; }
                    </style>
                </head>
                <body>
                    <header>
                        <div>
                            <h1>⚡ Nanometer Embedded APM</h1>
                            <div style="font-size: 13px; color: #94a3b8; margin-top: 4px;">Zero-Boilerplate Runtime Observability & Automated Graph Inference</div>
                        </div>
                        <span class="badge">LIVE TELEMETRY (WAL)</span>
                    </header>
                    <div class="grid">
                        <div class="card">
                            <h2>📊 Top Methods by Call Volume & P95 Latency</h2>
                            <table id="methods-table">
                                <thead>
                                    <tr><th>Method</th><th>Calls</th><th>Errors</th><th>p95 (ms)</th><th>Avg (ms)</th></tr>
                                </thead>
                                <tbody></tbody>
                            </table>
                        </div>
                        <div class="card">
                            <h2>🔗 Method Call Topology & Error Paths</h2>
                            <table id="edges-table">
                                <thead>
                                    <tr><th>Caller</th><th>Target</th><th>Calls</th><th>Errors</th></tr>
                                </thead>
                                <tbody></tbody>
                            </table>
                        </div>
                    </div>
                    <script>
                        async function refresh() {
                            try {
                                const res = await fetch('/api/graph');
                                const data = await res.json();
                                
                                const mBody = document.querySelector('#methods-table tbody');
                                mBody.innerHTML = data.nodes.map(n => `
                                    <tr>
                                        <td><strong>${n.label}</strong><br><small style="color:#64748b">${n.id}</small></td>
                                        <td>${n.calls}</td>
                                        <td class="${n.errors > 0 ? 'err' : ''}">${n.errors}</td>
                                        <td>${n.p95.toFixed(2)} ms</td>
                                        <td>${n.avg.toFixed(2)} ms</td>
                                    </tr>
                                `).join('');

                                const eBody = document.querySelector('#edges-table tbody');
                                eBody.innerHTML = data.edges.map(e => `
                                    <tr>
                                        <td>${e.source}</td>
                                        <td>${e.target}</td>
                                        <td>${e.calls}</td>
                                        <td class="${e.errors > 0 ? 'err' : ''}">${e.errors}</td>
                                    </tr>
                                `).join('');
                            } catch(e) {
                                console.error(e);
                            }
                        }
                        setInterval(refresh, 1000);
                        refresh();
                    </script>
                </body>
                </html>
            """;
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}

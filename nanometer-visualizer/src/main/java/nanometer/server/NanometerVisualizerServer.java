package nanometer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import nanometer.anomaly.AnomalyDetector;
import nanometer.anomaly.RootCauseAnalyzer;
import nanometer.discovery.GraphAutoDiscoveryEngine;
import nanometer.export.OtlpJsonExporter;
import nanometer.graph.GraphMetricAggregator;
import nanometer.profiling.JfrProfileSampler;
import nanometer.sampling.AdaptiveSampler;
import nanometer.storage.MetricDatabaseFlusher;
import nanometer.storage.MetricQueryService;
import nanometer.system.SystemMetricsSampler;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lightweight embedded visualizer server providing real-time APM telemetry, flamegraphs, SQL analytics, and root cause analysis.
 */
@AICore(sensitivity = "High", note = "Embedded zero-dependency HTTP visualizer server with analytics and flamegraphs")
@AIPublicAPI(reason = "Embedded APM server dashboard lifecycle and REST analytics APIs")
@AIObservability(metrics = {"http_requests_total"}, traces = {})
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Thread-safe server lifecycle management")
public class NanometerVisualizerServer {

    private final int port;
    private final GraphMetricAggregator aggregator;
    private final @Nullable MetricDatabaseFlusher flusher;
    private final @Nullable MetricQueryService queryService;
    private final AnomalyDetector anomalyDetector;
    private final JfrProfileSampler profileSampler;
    private final AdaptiveSampler adaptiveSampler;
    private @Nullable HttpServer server;

    public NanometerVisualizerServer(int port, GraphMetricAggregator aggregator, @Nullable MetricDatabaseFlusher flusher) {
        this(port, aggregator, flusher, null, null, null, null);
    }

    public NanometerVisualizerServer(
            int port,
            GraphMetricAggregator aggregator,
            @Nullable MetricDatabaseFlusher flusher,
            @Nullable MetricQueryService queryService,
            @Nullable AnomalyDetector anomalyDetector,
            @Nullable JfrProfileSampler profileSampler,
            @Nullable AdaptiveSampler adaptiveSampler
    ) {
        this.port = port;
        this.aggregator = aggregator;
        this.flusher = flusher;
        this.queryService = queryService;
        this.anomalyDetector = anomalyDetector != null ? anomalyDetector : new AnomalyDetector();
        this.profileSampler = profileSampler != null ? profileSampler : new JfrProfileSampler();
        this.adaptiveSampler = adaptiveSampler != null ? adaptiveSampler : new AdaptiveSampler();
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
            s.createContext("/", new DashboardHandler());
            s.createContext("/api/graph", new ApiGraphHandler());
            s.createContext("/api/system", new ApiSystemHandler());
            s.createContext("/api/flamegraph", new ApiFlamegraphHandler());
            s.createContext("/api/anomalies", new ApiAnomaliesHandler());
            s.createContext("/api/rca", new ApiRcaHandler());
            s.createContext("/api/sql", new ApiSqlHandler());
            s.createContext("/api/control", new ApiControlHandler());
            s.createContext("/api/otlp", new ApiOtlpHandler());
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
            sendJsonResponse(exchange, 200, json);
        }
    }

    private static class ApiSystemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String json = SystemMetricsSampler.captureSnapshot().toJson();
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class ApiFlamegraphHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String json = profileSampler.generateFlamegraphJson();
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class ApiAnomaliesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String json = anomalyDetector.getAnomaliesJson();
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class ApiRcaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            var findings = RootCauseAnalyzer.analyze(aggregator);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"findings\":[");
            List<String> list = findings.stream().map(RootCauseAnalyzer.RootCauseFinding::toJson).toList();
            sb.append(String.join(",", list));
            sb.append("],\"markdown\":");
            String md = RootCauseAnalyzer.generateMarkdownDiagnosis(aggregator);
            sb.append("\"").append(escapeJson(md)).append("\"}");
            sendJsonResponse(exchange, 200, sb.toString());
        }
    }

    private class ApiSqlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String sql = readBody(exchange);
            if (queryService == null) {
                sendJsonResponse(exchange, 503, "{\"error\":\"SQLite query service not initialized or database offline\"}");
                return;
            }
            var res = queryService.executeQuery(sql);
            sendJsonResponse(exchange, 200, res.toJson());
        }
    }

    private class ApiControlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 200, adaptiveSampler.toJson());
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = readBody(exchange);
                // Simple parser for key=value or rate=0.5
                if (body.contains("rate=")) {
                    try {
                        String val = body.split("rate=")[1].split("[&\\s]")[0];
                        adaptiveSampler.setSampleRate(Double.parseDouble(val));
                    } catch (Exception ignored) {}
                }
                if (body.contains("tail=")) {
                    try {
                        String val = body.split("tail=")[1].split("[&\\s]")[0];
                        adaptiveSampler.setTailSamplingEnabled(Boolean.parseBoolean(val));
                    } catch (Exception ignored) {}
                }
                sendJsonResponse(exchange, 200, adaptiveSampler.toJson());
                return;
            }
            exchange.sendResponseHeaders(405, -1);
        }
    }

    private class ApiOtlpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String json = OtlpJsonExporter.exportToJson("nanometer-service", List.of());
            sendJsonResponse(exchange, 200, json);
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toString(StandardCharsets.UTF_8);
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
                    <title>⚡ Nanometer: Embedded APM, Flamegraphs & SQL Analytics</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <style>
                        * { box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #070b14; color: #f1f5f9; margin: 0; padding: 18px; }
                        header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #1e293b; padding-bottom: 14px; margin-bottom: 14px; }
                        h1 { margin: 0; font-size: 20px; color: #38bdf8; display: flex; align-items: center; gap: 8px; }
                        .header-controls { display: flex; align-items: center; gap: 12px; }
                        .badge { background: #0284c7; color: white; padding: 4px 10px; border-radius: 9999px; font-size: 11px; font-weight: bold; }
                        
                        /* Tabs */
                        .nav-tabs { display: flex; gap: 8px; margin-bottom: 14px; border-bottom: 1px solid #1e293b; padding-bottom: 8px; }
                        .tab-btn { background: #0f172a; border: 1px solid #1e293b; color: #94a3b8; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 12px; font-weight: 600; }
                        .tab-btn.active { background: #0284c7; color: white; border-color: #0284c7; }

                        /* Toolbar */
                        .toolbar { background: #0f172a; border: 1px solid #1e293b; border-radius: 8px; padding: 10px 14px; margin-bottom: 14px; display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; }
                        .tool-group { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #94a3b8; }
                        select, input[type="text"], textarea, button { background: #1e293b; border: 1px solid #334155; color: #f8fafc; border-radius: 6px; padding: 5px 10px; font-size: 12px; outline: none; }
                        button { cursor: pointer; font-weight: 600; background: #0284c7; border-color: #0284c7; }
                        button:hover { opacity: 0.9; }
                        button.secondary { background: #334155; border-color: #475569; }
                        
                        /* KPIs */
                        .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 14px; }
                        .kpi-card { background: #0f172a; border: 1px solid #1e293b; border-radius: 8px; padding: 12px; }
                        .kpi-title { font-size: 10px; text-transform: uppercase; color: #94a3b8; font-weight: 600; margin-bottom: 4px; }
                        .kpi-val { font-size: 18px; font-weight: 700; color: #38bdf8; }
                        .kpi-sub { font-size: 11px; color: #64748b; margin-top: 4px; }
                        .progress-bar { width: 100%; height: 5px; background: #1e293b; border-radius: 3px; margin-top: 6px; overflow: hidden; }
                        .progress-fill { height: 100%; background: #38bdf8; width: 0%; transition: width 0.3s ease; }
                        .progress-fill.warn { background: #f59e0b; }
                        .progress-fill.danger { background: #ef4444; }

                        /* Grids */
                        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
                        @media (max-width: 1100px) { .grid-2 { grid-template-columns: 1fr; } }
                        
                        .card { background: #0f172a; border: 1px solid #1e293b; border-radius: 8px; padding: 14px; box-shadow: 0 4px 10px rgba(0,0,0,0.3); }
                        .card h2 { font-size: 14px; margin-top: 0; color: #cbd5e1; border-bottom: 1px solid #1e293b; padding-bottom: 8px; display: flex; justify-content: space-between; align-items: center; }
                        
                        #canvas-container { position: relative; width: 100%; height: 420px; background: #020617; border-radius: 6px; overflow: hidden; border: 1px solid #1e293b; }
                        canvas#topology-canvas { width: 100%; height: 100%; display: block; cursor: grab; }
                        .canvas-overlay { position: absolute; top: 10px; right: 10px; display: flex; gap: 6px; }
                        .canvas-overlay button { padding: 4px 8px; font-size: 11px; }

                        table { width: 100%; border-collapse: collapse; margin-top: 6px; font-size: 12px; }
                        th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid #1e293b; }
                        th { color: #94a3b8; font-weight: 600; position: sticky; top: 0; background: #0f172a; }
                        .err { color: #f87171; font-weight: bold; }
                        .chart-box { position: relative; height: 220px; width: 100%; }

                        /* Flamegraph & SQL View */
                        .tab-content { display: none; }
                        .tab-content.active { display: block; }
                        .flame-row { display: flex; flex-direction: column; gap: 4px; padding: 10px; background: #020617; border-radius: 6px; max-height: 400px; overflow-y: auto; }
                        .flame-bar { background: linear-gradient(90deg, #f59e0b, #ef4444); color: #000; font-weight: bold; font-size: 10px; padding: 4px 8px; border-radius: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-bottom: 2px; }
                    </style>
                </head>
                <body>
                    <header>
                        <div>
                            <h1>⚡ Nanometer Embedded APM</h1>
                            <div style="font-size: 11px; color: #94a3b8; margin-top: 2px;">Zero-Boilerplate Runtime Observability, Flamegraphs & Automated RCA</div>
                        </div>
                        <div class="header-controls">
                            <span class="badge" id="live-indicator">🔴 LIVE STREAMING (WAL)</span>
                        </div>
                    </header>

                    <!-- Navigation Tabs -->
                    <div class="nav-tabs">
                        <button class="tab-btn active" onclick="switchTab('tab-topology')">🌐 Topology & Metrics</button>
                        <button class="tab-btn" onclick="switchTab('tab-flamegraph')">🔥 JFR Flamegraphs</button>
                        <button class="tab-btn" onclick="switchTab('tab-rca')">🚨 Anomaly & Root Cause Analysis</button>
                        <button class="tab-btn" onclick="switchTab('tab-sql')">💾 SQLite SQL Console</button>
                        <button class="tab-btn" onclick="switchTab('tab-sampling')">⚙️ Runtime Control</button>
                    </div>

                    <!-- TAB 1: TOPOLOGY & TELEMETRY -->
                    <div id="tab-topology" class="tab-content active">
                        <div class="toolbar">
                            <div class="tool-group">
                                <span><strong>Round:</strong></span>
                                <select id="round-select" onchange="onRoundChange()"><option value="live">🔴 Live Telemetry</option></select>
                                <button onclick="captureRoundSnapshot()">📸 Snapshot Round</button>
                                <button class="secondary" onclick="clearRoundData()">🔄 Reset</button>
                            </div>
                            <div class="tool-group">
                                <span><strong>Filter:</strong></span>
                                <input type="text" id="node-search" placeholder="Search method..." oninput="onFilterChange()" style="width:140px">
                                <label><input type="checkbox" id="errors-only" onchange="onFilterChange()"> Errors Only</label>
                                <label><input type="checkbox" id="cluster-pkg" onchange="onClusterToggle()"> Group by Package</label>
                            </div>
                        </div>

                        <div class="kpi-grid">
                            <div class="kpi-card">
                                <div class="kpi-title">Process CPU</div>
                                <div class="kpi-val" id="kpi-cpu-proc">0.0%</div>
                                <div class="progress-bar"><div class="progress-fill" id="kpi-cpu-proc-fill"></div></div>
                                <div class="kpi-sub" id="kpi-cpu-sys">System CPU: 0.0%</div>
                            </div>
                            <div class="kpi-card">
                                <div class="kpi-title">JVM Heap Memory</div>
                                <div class="kpi-val" id="kpi-heap-used">0 MB</div>
                                <div class="progress-bar"><div class="progress-fill" id="kpi-heap-fill"></div></div>
                                <div class="kpi-sub" id="kpi-heap-max">Max: 0 MB</div>
                            </div>
                            <div class="kpi-card">
                                <div class="kpi-title">Non-Heap Memory</div>
                                <div class="kpi-val" id="kpi-non-heap">0 MB</div>
                                <div class="kpi-sub">Metaspace / Native</div>
                            </div>
                            <div class="kpi-card">
                                <div class="kpi-title">Active Threads</div>
                                <div class="kpi-val" id="kpi-threads">0</div>
                                <div class="kpi-sub" id="kpi-procs">0 Processors</div>
                            </div>
                            <div class="kpi-card">
                                <div class="kpi-title">Runtime Uptime</div>
                                <div class="kpi-val" id="kpi-uptime">0s</div>
                                <div class="kpi-sub" id="kpi-nodes-count">0 Nodes, 0 Edges</div>
                            </div>
                        </div>

                        <div class="grid-2">
                            <div class="card">
                                <h2>
                                    <span>🌐 Method Topology DAG & Failure Paths</span>
                                    <small style="font-size:11px;color:#64748b" id="cluster-label">Auto-Discovered Hierarchy</small>
                                </h2>
                                <div id="canvas-container">
                                    <canvas id="topology-canvas"></canvas>
                                    <div class="canvas-overlay">
                                        <button class="secondary" onclick="zoomIn()">➕</button>
                                        <button class="secondary" onclick="zoomOut()">➖</button>
                                        <button class="secondary" onclick="resetZoom()">Fit</button>
                                    </div>
                                </div>
                            </div>
                            <div class="card">
                                <h2>
                                    <span>📈 System CPU & Heap Memory Trend</span>
                                    <small style="font-size:11px;color:#64748b">Rolling 30s Time-Series</small>
                                </h2>
                                <div class="chart-box">
                                    <canvas id="system-chart"></canvas>
                                </div>
                            </div>
                        </div>

                        <div class="grid-2">
                            <div class="card">
                                <h2><span>📊 Top Slowest Methods (p95 Latency)</span></h2>
                                <div class="chart-box"><canvas id="latency-chart"></canvas></div>
                            </div>
                            <div class="card">
                                <h2><span>📋 Method Telemetry Grid</span></h2>
                                <div style="max-height: 220px; overflow-y: auto;">
                                    <table id="methods-table"><thead><tr><th>Method</th><th>Calls</th><th>Errors</th><th>p95</th><th>Avg</th></tr></thead><tbody></tbody></table>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- TAB 2: FLAMEGRAPHS -->
                    <div id="tab-flamegraph" class="tab-content">
                        <div class="card">
                            <h2>
                                <span>🔥 Execution Call-Tree Flamegraph</span>
                                <button onclick="fetchFlamegraph()">🔄 Refresh Tree</button>
                            </h2>
                            <div class="flame-row" id="flamegraph-container">
                                <div style="color:#64748b;padding:20px;text-align:center">Click 'Refresh Tree' to pull active stack frame profiles...</div>
                            </div>
                        </div>
                    </div>

                    <!-- TAB 3: ANOMALY & ROOT CAUSE ANALYSIS -->
                    <div id="tab-rca" class="tab-content">
                        <div class="grid-2">
                            <div class="card">
                                <h2><span>🚨 Automated Root Cause Explainer</span></h2>
                                <div id="rca-output" style="font-size:13px;line-height:1.6;color:#cbd5e1">Loading diagnosis...</div>
                            </div>
                            <div class="card">
                                <h2><span>📈 Statistical Outliers (3-Sigma Anomalies)</span></h2>
                                <div style="max-height:300px;overflow-y:auto">
                                    <table id="anomalies-table">
                                        <thead><tr><th>Method</th><th>Duration</th><th>Mean</th><th>Sigma</th></tr></thead>
                                        <tbody></tbody>
                                    </table>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- TAB 4: SQL ANALYTICS CONSOLE -->
                    <div id="tab-sql" class="tab-content">
                        <div class="card">
                            <h2><span>💾 SQLite Embedded Analytics Console</span></h2>
                            <div style="margin-bottom:10px">
                                <textarea id="sql-input" style="width:100%;height:70px;font-family:monospace">SELECT class_name, method_name, count(*) as calls, round(avg(duration_nanos)/1000000.0, 2) as avg_ms FROM metrics GROUP BY class_name, method_name ORDER BY avg_ms DESC LIMIT 10;</textarea>
                            </div>
                            <div style="display:flex;gap:10px;margin-bottom:14px">
                                <button onclick="runSql()">▶ Execute Query</button>
                                <button class="secondary" onclick="setQueryTemplate(1)">Top Slowest</button>
                                <button class="secondary" onclick="setQueryTemplate(2)">Exception Breakdown</button>
                                <button class="secondary" onclick="setQueryTemplate(3)">Timeline</button>
                                <span id="sql-timing" style="font-size:12px;color:#64748b;align-self:center"></span>
                            </div>
                            <div style="max-height:280px;overflow-y:auto">
                                <table id="sql-results-table"><thead></thead><tbody></tbody></table>
                            </div>
                        </div>
                    </div>

                    <!-- TAB 5: RUNTIME CONTROL -->
                    <div id="tab-sampling" class="tab-content">
                        <div class="card">
                            <h2><span>⚙️ Dynamic Sampling & Package Filters</span></h2>
                            <div style="display:flex;flex-direction:column;gap:14px;max-width:500px">
                                <div>
                                    <label><strong>Sample Rate:</strong> <span id="sample-rate-val">100%</span></label>
                                    <input type="range" id="sample-rate-slider" min="0" max="1" step="0.05" value="1.0" oninput="onSampleRateChange(this.value)" style="width:100%">
                                </div>
                                <div>
                                    <label><input type="checkbox" id="tail-sampling-toggle" checked onchange="onTailSamplingChange(this.checked)"> <strong>Adaptive Tail Sampling</strong> (Keep 100% of errors & slow traces)</label>
                                </div>
                            </div>
                        </div>
                    </div>

                    <script>
                        function switchTab(tabId) {
                            document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
                            document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
                            document.getElementById(tabId).classList.add('active');
                            event.target.classList.add('active');
                            if (tabId === 'tab-flamegraph') fetchFlamegraph();
                            if (tabId === 'tab-rca') fetchRca();
                        }

                        // SQL console execution
                        async function runSql() {
                            const query = document.getElementById('sql-input').value;
                            const res = await fetch('/api/sql', { method: 'POST', body: query });
                            const data = await res.json();
                            const thead = document.querySelector('#sql-results-table thead');
                            const tbody = document.querySelector('#sql-results-table tbody');
                            if (data.error) {
                                thead.innerHTML = '';
                                tbody.innerHTML = `<tr><td style="color:#ef4444">${data.error}</td></tr>`;
                                return;
                            }
                            thead.innerHTML = `<tr>${data.columns.map(c => `<th>${c}</th>`).join('')}</tr>`;
                            tbody.innerHTML = data.rows.map(r => `<tr>${r.map(v => `<td>${v}</td>`).join('')}</tr>`).join('');
                            document.getElementById('sql-timing').innerText = `Executed in ${data.executionTimeMs} ms (${data.rows.length} rows)`;
                        }

                        function setQueryTemplate(idx) {
                            const q1 = "SELECT class_name, method_name, count(*) as calls, round(avg(duration_nanos)/1000000.0, 2) as avg_ms FROM metrics GROUP BY class_name, method_name ORDER BY avg_ms DESC LIMIT 10;";
                            const q2 = "SELECT exception_type, count(*) as error_count FROM metrics WHERE exception_type != 'NONE' GROUP BY exception_type ORDER BY error_count DESC;";
                            const q3 = "SELECT strftime('%H:%M:%S', timestamp / 1000, 'unixepoch') as sec_bucket, count(*) as calls FROM metrics GROUP BY sec_bucket ORDER BY sec_bucket DESC LIMIT 15;";
                            document.getElementById('sql-input').value = idx === 1 ? q1 : (idx === 2 ? q2 : q3);
                            runSql();
                        }

                        // RCA fetch
                        async function fetchRca() {
                            const res = await fetch('/api/rca');
                            const data = await res.json();
                            document.getElementById('rca-output').innerHTML = data.markdown.replace(/\\n/g, '<br>').replace(/\\|/g, ' ');
                            
                            const aRes = await fetch('/api/anomalies');
                            const aData = await aRes.json();
                            const aBody = document.querySelector('#anomalies-table tbody');
                            aBody.innerHTML = aData.map(a => `
                                <tr>
                                    <td>${a.methodKey}</td>
                                    <td>${a.observedMs.toFixed(2)} ms</td>
                                    <td>${a.meanMs.toFixed(2)} ms</td>
                                    <td class="err">+${a.sigma.toFixed(1)}σ</td>
                                </tr>
                            `).join('');
                        }

                        // Flamegraph fetch
                        async function fetchFlamegraph() {
                            const res = await fetch('/api/flamegraph');
                            const tree = await res.json();
                            const container = document.getElementById('flamegraph-container');
                            container.innerHTML = '';
                            function renderNode(node, depth) {
                                if (node.name !== 'root') {
                                    const div = document.createElement('div');
                                    div.className = 'flame-bar';
                                    div.style.marginLeft = (depth * 14) + 'px';
                                    div.innerText = `${node.name} (${(node.value / 1000000).toFixed(1)} ms)`;
                                    container.appendChild(div);
                                }
                                if (node.children) {
                                    node.children.forEach(c => renderNode(c, depth + 1));
                                }
                            }
                            renderNode(tree, 0);
                        }

                        async function onSampleRateChange(val) {
                            document.getElementById('sample-rate-val').innerText = Math.round(val * 100) + '%';
                            await fetch('/api/control', { method: 'POST', body: 'rate=' + val });
                        }

                        async function onTailSamplingChange(val) {
                            await fetch('/api/control', { method: 'POST', body: 'tail=' + val });
                        }

                        // Topology & Charts Engine (inherited from previous version)
                        const roundsHistory = new Map();
                        let currentRound = 'live';
                        let roundCounter = 1;
                        const maxHistory = 25;
                        const timeLabels = [], cpuData = [], heapData = [];
                        let zoom = 1.0, panX = 0, panY = 0, isDragging = false, dragStartX = 0, dragStartY = 0, selectedNode = null, groupByPackage = false;

                        const sysCtx = document.getElementById('system-chart').getContext('2d');
                        const systemChart = new Chart(sysCtx, {
                            type: 'line',
                            data: {
                                labels: timeLabels,
                                datasets: [
                                    { label: 'Process CPU (%)', data: cpuData, borderColor: '#38bdf8', backgroundColor: 'rgba(56,189,248,0.1)', yAxisID: 'yCpu', fill: true, tension: 0.3 },
                                    { label: 'Heap Used (MB)', data: heapData, borderColor: '#a855f7', backgroundColor: 'rgba(168,85,247,0.1)', yAxisID: 'yMem', fill: true, tension: 0.3 }
                                ]
                            },
                            options: {
                                responsive: true, maintainAspectRatio: false, animation: false,
                                scales: {
                                    x: { grid: { color: '#1e293b' }, ticks: { color: '#64748b', font: { size: 10 } } },
                                    yCpu: { position: 'left', min: 0, max: 100, grid: { color: '#1e293b' }, ticks: { color: '#38bdf8', callback: v => v + '%' } },
                                    yMem: { position: 'right', min: 0, grid: { drawOnChartArea: false }, ticks: { color: '#a855f7', callback: v => v + ' MB' } }
                                },
                                plugins: { legend: { labels: { color: '#94a3b8', boxWidth: 12 } } }
                            }
                        });

                        const latCtx = document.getElementById('latency-chart').getContext('2d');
                        const latencyChart = new Chart(latCtx, {
                            type: 'bar',
                            data: { labels: [], datasets: [{ label: 'p95 (ms)', data: [], backgroundColor: '#38bdf8' }, { label: 'Avg (ms)', data: [], backgroundColor: '#0284c7' }] },
                            options: {
                                responsive: true, maintainAspectRatio: false, indexAxis: 'y',
                                scales: { x: { grid: { color: '#1e293b' }, ticks: { color: '#94a3b8' } }, y: { grid: { color: '#1e293b' }, ticks: { color: '#cbd5e1', font: { size: 11 } } } },
                                plugins: { legend: { labels: { color: '#94a3b8', boxWidth: 12 } } }
                            }
                        });

                        const canvas = document.getElementById('topology-canvas');
                        const ctx = canvas.getContext('2d');
                        let rawNodes = [], rawEdges = [], displayNodes = [], displayEdges = [];

                        function resizeCanvas() {
                            const rect = canvas.parentElement.getBoundingClientRect();
                            canvas.width = rect.width;
                            canvas.height = rect.height;
                        }
                        window.addEventListener('resize', resizeCanvas);
                        resizeCanvas();

                        canvas.addEventListener('mousedown', e => {
                            isDragging = true;
                            dragStartX = e.clientX - panX;
                            dragStartY = e.clientY - panY;
                        });
                        window.addEventListener('mousemove', e => { if (isDragging) { panX = e.clientX - dragStartX; panY = e.clientY - dragStartY; } });
                        window.addEventListener('mouseup', () => isDragging = false);
                        canvas.addEventListener('wheel', e => { e.preventDefault(); zoom = Math.max(0.2, Math.min(4.0, zoom * (e.deltaY < 0 ? 1.1 : 0.9))); });
                        function zoomIn() { zoom = Math.min(4.0, zoom * 1.2); }
                        function zoomOut() { zoom = Math.max(0.2, zoom * 0.8); }
                        function resetZoom() { zoom = 1.0; panX = 0; panY = 0; }

                        function onClusterToggle() {
                            groupByPackage = document.getElementById('cluster-pkg').checked;
                            document.getElementById('cluster-label').innerText = groupByPackage ? 'Grouped by Package Clusters' : 'Auto-Discovered Hierarchy';
                            filterAndLayoutNodes();
                        }
                        function onFilterChange() { filterAndLayoutNodes(); }

                        function filterAndLayoutNodes() {
                            const search = document.getElementById('node-search').value.toLowerCase();
                            const errOnly = document.getElementById('errors-only').checked;

                            let filtered = rawNodes.filter(n => {
                                if (search && !n.id.toLowerCase().includes(search) && !n.label.toLowerCase().includes(search)) return false;
                                if (errOnly && n.errors === 0 && !n.isException) return false;
                                return true;
                            });

                            if (groupByPackage) {
                                const pkgMap = new Map();
                                filtered.forEach(n => {
                                    const pkg = n.id.includes('.') ? n.id.substring(0, n.id.lastIndexOf('.')) : n.id;
                                    if (!pkgMap.has(pkg)) {
                                        pkgMap.set(pkg, { id: pkg, label: pkg.split('.').pop() || pkg, calls: 0, errors: 0, p95: 0, avg: 0, isException: n.isException });
                                    }
                                    const p = pkgMap.get(pkg);
                                    p.calls += n.calls; p.errors += n.errors; p.p95 = Math.max(p.p95, n.p95);
                                });
                                filtered = Array.from(pkgMap.values());
                            }

                            const existingMap = new Map(displayNodes.map(n => [n.id, n]));
                            const cx = canvas.width / 2, cy = canvas.height / 2;
                            const radius = Math.min(canvas.width, canvas.height) * 0.38;

                            displayNodes = filtered.map((n, i) => {
                                const prev = existingMap.get(n.id);
                                const angle = (i / Math.max(1, filtered.length)) * Math.PI * 2;
                                return {
                                    ...n,
                                    x: prev ? prev.x : cx + Math.cos(angle) * radius,
                                    y: prev ? prev.y : cy + Math.sin(angle) * radius,
                                    radius: groupByPackage ? 24 : 16
                                };
                            });

                            const nodeIds = new Set(displayNodes.map(n => n.id));
                            displayEdges = rawEdges.filter(e => nodeIds.has(e.source) && nodeIds.has(e.target));
                        }

                        function renderCanvas() {
                            ctx.clearRect(0, 0, canvas.width, canvas.height);
                            ctx.save();
                            ctx.translate(panX, panY);
                            ctx.scale(zoom, zoom);

                            if (displayNodes.length === 0) {
                                ctx.fillStyle = '#64748b'; ctx.font = '13px sans-serif'; ctx.textAlign = 'center';
                                ctx.fillText('No method nodes match current filters...', (canvas.width / 2 - panX) / zoom, (canvas.height / 2 - panY) / zoom);
                                ctx.restore();
                                requestAnimationFrame(renderCanvas);
                                return;
                            }

                            const map = new Map(displayNodes.map(n => [n.id, n]));
                            displayEdges.forEach(e => {
                                const src = map.get(e.source), tgt = map.get(e.target);
                                if (!src || !tgt) return;
                                ctx.beginPath(); ctx.moveTo(src.x, src.y); ctx.lineTo(tgt.x, tgt.y);
                                ctx.strokeStyle = e.errors > 0 ? '#ef4444' : '#0284c7';
                                ctx.lineWidth = Math.min(5, Math.max(1.5, Math.log10(e.calls + 1)));
                                ctx.stroke();
                            });

                            displayNodes.forEach(n => {
                                const isErr = n.isException || n.errors > 0;
                                ctx.beginPath(); ctx.arc(n.x, n.y, n.radius, 0, Math.PI * 2);
                                ctx.fillStyle = isErr ? '#ef4444' : (n.p95 > 25 ? '#f59e0b' : '#0284c7');
                                ctx.fill(); ctx.lineWidth = 1.5; ctx.strokeStyle = '#ffffff'; ctx.stroke();
                                ctx.fillStyle = '#f8fafc'; ctx.font = (n.radius > 18 ? '12px' : '10px') + ' sans-serif';
                                ctx.textAlign = 'center'; ctx.fillText(n.label, n.x, n.y - n.radius - 6);
                            });

                            ctx.restore();
                            requestAnimationFrame(renderCanvas);
                        }
                        renderCanvas();

                        function captureRoundSnapshot() {
                            const name = 'Round ' + roundCounter++;
                            const snapshot = { nodes: JSON.parse(JSON.stringify(rawNodes)), edges: JSON.parse(JSON.stringify(rawEdges)), timestamp: new Date().toLocaleTimeString() };
                            roundsHistory.set(name, snapshot);
                            const select = document.getElementById('round-select');
                            const opt = document.createElement('option');
                            opt.value = name; opt.innerText = '📷 ' + name + ' (' + snapshot.timestamp + ')';
                            select.appendChild(opt); select.value = name;
                        }

                        function onRoundChange() {
                            const val = document.getElementById('round-select').value;
                            currentRound = val;
                            if (val !== 'live') {
                                const snap = roundsHistory.get(val);
                                if (snap) { rawNodes = snap.nodes; rawEdges = snap.edges; filterAndLayoutNodes(); updateTablesAndCharts(rawNodes); }
                            }
                        }

                        function clearRoundData() { rawNodes = []; rawEdges = []; displayNodes = []; displayEdges = []; filterAndLayoutNodes(); }

                        function updateTablesAndCharts(nodesList) {
                            const mBody = document.querySelector('#methods-table tbody');
                            const sortedNodes = [...nodesList].sort((a, b) => b.calls - a.calls);
                            mBody.innerHTML = sortedNodes.map(n => `
                                <tr>
                                    <td><strong>${n.label}</strong><br><small style="color:#64748b">${n.id}</small></td>
                                    <td>${n.calls}</td>
                                    <td class="${n.errors > 0 ? 'err' : ''}">${n.errors}</td>
                                    <td>${n.p95.toFixed(2)} ms</td>
                                    <td>${n.avg.toFixed(2)} ms</td>
                                </tr>
                            `).join('');
                            const topMethods = sortedNodes.filter(n => !n.isException).slice(0, 8);
                            latencyChart.data.labels = topMethods.map(n => n.label);
                            latencyChart.data.datasets[0].data = topMethods.map(n => n.p95);
                            latencyChart.data.datasets[1].data = topMethods.map(n => n.avg);
                            latencyChart.update();
                        }

                        async function pollTelemetry() {
                            if (currentRound !== 'live') return;
                            try {
                                const res = await fetch('/api/graph');
                                const data = await res.json();
                                if (data.system) {
                                    const s = data.system;
                                    document.getElementById('kpi-cpu-proc').innerText = s.processCpu.toFixed(1) + '%';
                                    document.getElementById('kpi-cpu-sys').innerText = 'System CPU: ' + s.systemCpu.toFixed(1) + '%';
                                    document.getElementById('kpi-cpu-proc-fill').style.width = Math.min(100, s.processCpu) + '%';
                                    document.getElementById('kpi-heap-used').innerText = s.heapUsedMb + ' MB';
                                    document.getElementById('kpi-heap-max').innerText = 'Max: ' + s.heapMaxMb + ' MB';
                                    const heapPct = s.heapMaxMb > 0 ? (s.heapUsedMb / s.heapMaxMb) * 100 : 0;
                                    document.getElementById('kpi-heap-fill').style.width = Math.min(100, heapPct) + '%';
                                    document.getElementById('kpi-non-heap').innerText = s.nonHeapUsedMb + ' MB';
                                    document.getElementById('kpi-threads').innerText = s.threadCount;
                                    document.getElementById('kpi-procs').innerText = s.processors + ' Cores';
                                    const secs = Math.floor(s.uptimeMs / 1000);
                                    document.getElementById('kpi-uptime').innerText = secs + 's';
                                    timeLabels.push(new Date().toLocaleTimeString().split(' ')[0]);
                                    cpuData.push(s.processCpu);
                                    heapData.push(s.heapUsedMb);
                                    if (timeLabels.length > maxHistory) { timeLabels.shift(); cpuData.shift(); heapData.shift(); }
                                    systemChart.update();
                                }
                                rawNodes = data.nodes || [];
                                rawEdges = data.edges || [];
                                document.getElementById('kpi-nodes-count').innerText = rawNodes.length + ' Nodes, ' + rawEdges.length + ' Edges';
                                filterAndLayoutNodes();
                                updateTablesAndCharts(rawNodes);
                            } catch (e) {}
                        }
                        setInterval(pollTelemetry, 1000);
                        pollTelemetry();
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

    private static String escapeJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}

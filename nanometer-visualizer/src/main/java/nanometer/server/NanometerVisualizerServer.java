package nanometer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import nanometer.discovery.GraphAutoDiscoveryEngine;
import nanometer.graph.GraphMetricAggregator;
import nanometer.storage.MetricDatabaseFlusher;
import nanometer.system.SystemMetricsSampler;
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
 * Lightweight embedded visualizer server providing real-time APM telemetry, topology graphs, and system metrics.
 */
@AICore(sensitivity = "High", note = "Embedded zero-dependency HTTP visualizer server with charts and topology")
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
        if (server != null) {
            return;
        }
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
            s.createContext("/", new DashboardHandler());
            s.createContext("/api/graph", new ApiGraphHandler());
            s.createContext("/api/system", new ApiSystemHandler());
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

    private static class ApiSystemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String json = SystemMetricsSampler.captureSnapshot().toJson();
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
                    <title>⚡ Nanometer: Embedded APM & Multi-Round Topology Visualizer</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <style>
                        * { box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #070b14; color: #f1f5f9; margin: 0; padding: 18px; }
                        header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #1e293b; padding-bottom: 14px; margin-bottom: 16px; }
                        h1 { margin: 0; font-size: 20px; color: #38bdf8; display: flex; align-items: center; gap: 8px; }
                        .header-controls { display: flex; align-items: center; gap: 12px; }
                        .badge { background: #0284c7; color: white; padding: 4px 10px; border-radius: 9999px; font-size: 11px; font-weight: bold; }
                        
                        /* Multi-round toolbar */
                        .toolbar { background: #0f172a; border: 1px solid #1e293b; border-radius: 8px; padding: 10px 14px; margin-bottom: 16px; display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; }
                        .tool-group { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #94a3b8; }
                        select, input[type="text"], button { background: #1e293b; border: 1px solid #334155; color: #f8fafc; border-radius: 6px; padding: 5px 10px; font-size: 12px; outline: none; }
                        button { cursor: pointer; font-weight: 600; background: #0284c7; border-color: #0284c7; transition: opacity 0.2s; }
                        button:hover { opacity: 0.9; }
                        button.secondary { background: #334155; border-color: #475569; }
                        
                        /* KPIs */
                        .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 16px; }
                        .kpi-card { background: #0f172a; border: 1px solid #1e293b; border-radius: 8px; padding: 12px; }
                        .kpi-title { font-size: 10px; text-transform: uppercase; color: #94a3b8; font-weight: 600; margin-bottom: 4px; }
                        .kpi-val { font-size: 18px; font-weight: 700; color: #38bdf8; }
                        .kpi-sub { font-size: 11px; color: #64748b; margin-top: 4px; }
                        .progress-bar { width: 100%; height: 5px; background: #1e293b; border-radius: 3px; margin-top: 6px; overflow: hidden; }
                        .progress-fill { height: 100%; background: #38bdf8; width: 0%; transition: width 0.3s ease; }
                        .progress-fill.warn { background: #f59e0b; }
                        .progress-fill.danger { background: #ef4444; }

                        /* Layout grids */
                        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
                        @media (max-width: 1100px) { .grid-2 { grid-template-columns: 1fr; } }
                        
                        .card { background: #0f172a; border: 1px solid #1e293b; border-radius: 8px; padding: 14px; box-shadow: 0 4px 10px rgba(0,0,0,0.3); }
                        .card h2 { font-size: 14px; margin-top: 0; color: #cbd5e1; border-bottom: 1px solid #1e293b; padding-bottom: 8px; display: flex; justify-content: space-between; align-items: center; }
                        
                        /* Canvas Container for Massive Topologies */
                        #canvas-container { position: relative; width: 100%; height: 420px; background: #020617; border-radius: 6px; overflow: hidden; border: 1px solid #1e293b; }
                        canvas#topology-canvas { width: 100%; height: 100%; display: block; cursor: grab; }
                        canvas#topology-canvas:active { cursor: grabbing; }
                        .canvas-overlay { position: absolute; top: 10px; right: 10px; display: flex; gap: 6px; }
                        .canvas-overlay button { padding: 4px 8px; font-size: 11px; }

                        /* Tables & Charts */
                        table { width: 100%; border-collapse: collapse; margin-top: 6px; font-size: 12px; }
                        th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid #1e293b; }
                        th { color: #94a3b8; font-weight: 600; position: sticky; top: 0; background: #0f172a; }
                        .err { color: #f87171; font-weight: bold; }
                        .chart-box { position: relative; height: 220px; width: 100%; }

                        /* Node Inspector Drawer */
                        #node-inspector { display: none; margin-top: 10px; padding: 10px; background: #1e293b; border-radius: 6px; font-size: 12px; }
                    </style>
                </head>
                <body>
                    <header>
                        <div>
                            <h1>⚡ Nanometer Embedded APM</h1>
                            <div style="font-size: 11px; color: #94a3b8; margin-top: 2px;">Zero-Boilerplate Runtime Observability & Massive Topology Inference</div>
                        </div>
                        <div class="header-controls">
                            <span class="badge" id="live-indicator">🔴 LIVE STREAMING (WAL)</span>
                        </div>
                    </header>

                    <!-- Multi-Round & Scalability Controls -->
                    <div class="toolbar">
                        <div class="tool-group">
                            <span><strong>Round:</strong></span>
                            <select id="round-select" onchange="onRoundChange()">
                                <option value="live">🔴 Live Telemetry</option>
                            </select>
                            <button onclick="captureRoundSnapshot()">📸 Snapshot Round</button>
                            <button class="secondary" onclick="clearRoundData()">🔄 Reset Telemetry</button>
                        </div>
                        <div class="tool-group">
                            <span><strong>Filter:</strong></span>
                            <input type="text" id="node-search" placeholder="Search method / class..." oninput="onFilterChange()" style="width:160px">
                            <label><input type="checkbox" id="errors-only" onchange="onFilterChange()"> Errors Only</label>
                            <label><input type="checkbox" id="cluster-pkg" onchange="onClusterToggle()"> Group by Package</label>
                            <span>Min Calls:</span>
                            <select id="min-calls-filter" onchange="onFilterChange()">
                                <option value="0">0+</option>
                                <option value="5">5+</option>
                                <option value="20">20+</option>
                                <option value="50">50+</option>
                            </select>
                        </div>
                    </div>

                    <!-- System & JVM Telemetry Gauges -->
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

                    <!-- Topology DAG Canvas & System Charts -->
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
                            <div id="node-inspector">
                                <strong>Node Inspector:</strong> <span id="insp-name" style="color:#38bdf8"></span> | 
                                Calls: <span id="insp-calls"></span> | Errors: <span id="insp-errors" class="err"></span> | 
                                P95: <span id="insp-p95"></span>
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

                    <!-- Latency Breakdown Chart & Method Telemetry Table -->
                    <div class="grid-2">
                        <div class="card">
                            <h2>
                                <span>📊 Top Slowest Methods (p95 Latency)</span>
                                <small style="font-size:11px;color:#64748b">Milliseconds</small>
                            </h2>
                            <div class="chart-box">
                                <canvas id="latency-chart"></canvas>
                            </div>
                        </div>
                        <div class="card">
                            <h2>
                                <span>📋 Method Telemetry Grid</span>
                            </h2>
                            <div style="max-height: 220px; overflow-y: auto;">
                                <table id="methods-table">
                                    <thead>
                                        <tr><th>Method</th><th>Calls</th><th>Errors</th><th>p95</th><th>Avg</th></tr>
                                    </thead>
                                    <tbody></tbody>
                                </table>
                            </div>
                        </div>
                    </div>

                    <script>
                        // --- Multi-Round State & History Storage ---
                        const roundsHistory = new Map();
                        let currentRound = 'live';
                        let roundCounter = 1;

                        const maxHistory = 25;
                        const timeLabels = [];
                        const cpuData = [];
                        const heapData = [];

                        // Canvas pan & zoom state for huge topologies
                        let zoom = 1.0;
                        let panX = 0;
                        let panY = 0;
                        let isDragging = false;
                        let dragStartX = 0;
                        let dragStartY = 0;
                        let selectedNode = null;
                        let groupByPackage = false;

                        // Charts init
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
                                responsive: true,
                                maintainAspectRatio: false,
                                animation: false,
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
                                responsive: true,
                                maintainAspectRatio: false,
                                indexAxis: 'y',
                                scales: {
                                    x: { grid: { color: '#1e293b' }, ticks: { color: '#94a3b8' } },
                                    y: { grid: { color: '#1e293b' }, ticks: { color: '#cbd5e1', font: { size: 11 } } }
                                },
                                plugins: { legend: { labels: { color: '#94a3b8', boxWidth: 12 } } }
                            }
                        });

                        // Topology Canvas
                        const canvas = document.getElementById('topology-canvas');
                        const ctx = canvas.getContext('2d');
                        let rawNodes = [];
                        let rawEdges = [];
                        let displayNodes = [];
                        let displayEdges = [];

                        function resizeCanvas() {
                            const rect = canvas.parentElement.getBoundingClientRect();
                            canvas.width = rect.width;
                            canvas.height = rect.height;
                        }
                        window.addEventListener('resize', resizeCanvas);
                        resizeCanvas();

                        // Canvas Mouse Listeners (Pan, Zoom, Click)
                        canvas.addEventListener('mousedown', e => {
                            isDragging = true;
                            dragStartX = e.clientX - panX;
                            dragStartY = e.clientY - panY;
                            
                            // Check node click
                            const rect = canvas.getBoundingClientRect();
                            const mx = (e.clientX - rect.left - panX) / zoom;
                            const my = (e.clientY - rect.top - panY) / zoom;
                            selectedNode = displayNodes.find(n => Math.hypot(n.x - mx, n.y - my) <= (n.radius || 18));
                            updateInspector();
                        });

                        window.addEventListener('mousemove', e => {
                            if (isDragging) {
                                panX = e.clientX - dragStartX;
                                panY = e.clientY - dragStartY;
                            }
                        });

                        window.addEventListener('mouseup', () => isDragging = false);

                        canvas.addEventListener('wheel', e => {
                            e.preventDefault();
                            const zoomFactor = e.deltaY < 0 ? 1.1 : 0.9;
                            zoom = Math.max(0.2, Math.min(4.0, zoom * zoomFactor));
                        });

                        function zoomIn() { zoom = Math.min(4.0, zoom * 1.2); }
                        function zoomOut() { zoom = Math.max(0.2, zoom * 0.8); }
                        function resetZoom() { zoom = 1.0; panX = 0; panY = 0; }

                        function onClusterToggle() {
                            groupByPackage = document.getElementById('cluster-pkg').checked;
                            document.getElementById('cluster-label').innerText = groupByPackage ? 'Grouped by Package Clusters' : 'Auto-Discovered Hierarchy';
                            filterAndLayoutNodes();
                        }

                        function onFilterChange() {
                            filterAndLayoutNodes();
                        }

                        function updateInspector() {
                            const insp = document.getElementById('node-inspector');
                            if (!selectedNode) {
                                insp.style.display = 'none';
                                return;
                            }
                            insp.style.display = 'block';
                            document.getElementById('insp-name').innerText = selectedNode.id;
                            document.getElementById('insp-calls').innerText = selectedNode.calls;
                            document.getElementById('insp-errors').innerText = selectedNode.errors;
                            document.getElementById('insp-p95').innerText = selectedNode.p95.toFixed(2) + ' ms';
                        }

                        function filterAndLayoutNodes() {
                            const search = document.getElementById('node-search').value.toLowerCase();
                            const errOnly = document.getElementById('errors-only').checked;
                            const minCalls = parseInt(document.getElementById('min-calls-filter').value, 10) || 0;

                            let filtered = rawNodes.filter(n => {
                                if (search && !n.id.toLowerCase().includes(search) && !n.label.toLowerCase().includes(search)) return false;
                                if (errOnly && n.errors === 0 && !n.isException) return false;
                                if (n.calls < minCalls && !n.isException) return false;
                                return true;
                            });

                            if (groupByPackage) {
                                // Cluster into packages
                                const pkgMap = new Map();
                                filtered.forEach(n => {
                                    const pkg = n.id.includes('.') ? n.id.substring(0, n.id.lastIndexOf('.')) : n.id;
                                    if (!pkgMap.has(pkg)) {
                                        pkgMap.set(pkg, { id: pkg, label: pkg.split('.').pop() || pkg, calls: 0, errors: 0, p95: 0, avg: 0, isException: n.isException });
                                    }
                                    const p = pkgMap.get(pkg);
                                    p.calls += n.calls;
                                    p.errors += n.errors;
                                    p.p95 = Math.max(p.p95, n.p95);
                                });
                                filtered = Array.from(pkgMap.values());
                            }

                            const existingMap = new Map(displayNodes.map(n => [n.id, n]));
                            const cx = canvas.width / 2;
                            const cy = canvas.height / 2;
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

                        // Drawing loop
                        function renderCanvas() {
                            ctx.clearRect(0, 0, canvas.width, canvas.height);
                            ctx.save();
                            ctx.translate(panX, panY);
                            ctx.scale(zoom, zoom);

                            if (displayNodes.length === 0) {
                                ctx.fillStyle = '#64748b';
                                ctx.font = '13px sans-serif';
                                ctx.textAlign = 'center';
                                ctx.fillText('No method nodes match current filters...', (canvas.width / 2 - panX) / zoom, (canvas.height / 2 - panY) / zoom);
                                ctx.restore();
                                requestAnimationFrame(renderCanvas);
                                return;
                            }

                            // Force relaxation on nodes
                            for (let i = 0; i < displayNodes.length; i++) {
                                for (let j = i + 1; j < displayNodes.length; j++) {
                                    let dx = displayNodes[j].x - displayNodes[i].x;
                                    let dy = displayNodes[j].y - displayNodes[i].y;
                                    let dist = Math.hypot(dx, dy) || 1;
                                    if (dist < 160) {
                                        let force = 1200 / (dist * dist);
                                        let fx = (dx / dist) * force;
                                        let fy = (dy / dist) * force;
                                        displayNodes[i].x -= fx;
                                        displayNodes[i].y -= fy;
                                        displayNodes[j].x += fx;
                                        displayNodes[j].y += fy;
                                    }
                                }
                            }

                            const map = new Map(displayNodes.map(n => [n.id, n]));

                            // Draw Edges
                            displayEdges.forEach(e => {
                                const src = map.get(e.source);
                                const tgt = map.get(e.target);
                                if (!src || !tgt) return;

                                ctx.beginPath();
                                ctx.moveTo(src.x, src.y);
                                ctx.lineTo(tgt.x, tgt.y);
                                ctx.strokeStyle = e.errors > 0 ? '#ef4444' : '#0284c7';
                                ctx.lineWidth = Math.min(5, Math.max(1.5, Math.log10(e.calls + 1)));
                                ctx.stroke();

                                // Arrow
                                const angle = Math.atan2(tgt.y - src.y, tgt.x - src.x);
                                const ax = tgt.x - Math.cos(angle) * (tgt.radius + 6);
                                const ay = tgt.y - Math.sin(angle) * (tgt.radius + 6);
                                ctx.beginPath();
                                ctx.moveTo(ax, ay);
                                ctx.lineTo(ax - 8 * Math.cos(angle - Math.PI / 6), ay - 8 * Math.sin(angle - Math.PI / 6));
                                ctx.lineTo(ax - 8 * Math.cos(angle + Math.PI / 6), ay - 8 * Math.sin(angle + Math.PI / 6));
                                ctx.fillStyle = e.errors > 0 ? '#ef4444' : '#38bdf8';
                                ctx.fill();
                            });

                            // Draw Nodes
                            displayNodes.forEach(n => {
                                const isSel = selectedNode && selectedNode.id === n.id;
                                const isErr = n.isException || n.errors > 0;
                                ctx.beginPath();
                                ctx.arc(n.x, n.y, n.radius, 0, Math.PI * 2);
                                ctx.fillStyle = isErr ? '#ef4444' : (n.p95 > 25 ? '#f59e0b' : '#0284c7');
                                ctx.fill();
                                ctx.lineWidth = isSel ? 3 : 1.5;
                                ctx.strokeStyle = isSel ? '#38bdf8' : '#ffffff';
                                ctx.stroke();

                                // Label
                                ctx.fillStyle = '#f8fafc';
                                ctx.font = (n.radius > 18 ? '12px' : '10px') + ' sans-serif';
                                ctx.textAlign = 'center';
                                ctx.fillText(n.label, n.x, n.y - n.radius - 6);

                                if (n.calls > 0) {
                                    ctx.fillStyle = '#94a3b8';
                                    ctx.font = '9px sans-serif';
                                    ctx.fillText(n.calls + ' calls', n.x, n.y + n.radius + 12);
                                }
                            });

                            ctx.restore();
                            requestAnimationFrame(renderCanvas);
                        }
                        renderCanvas();

                        // Multi-Round Snapshot & Controls
                        function captureRoundSnapshot() {
                            const name = 'Round ' + roundCounter++;
                            const snapshot = {
                                nodes: JSON.parse(JSON.stringify(rawNodes)),
                                edges: JSON.parse(JSON.stringify(rawEdges)),
                                timestamp: new Date().toLocaleTimeString()
                            };
                            roundsHistory.set(name, snapshot);
                            
                            const select = document.getElementById('round-select');
                            const opt = document.createElement('option');
                            opt.value = name;
                            opt.innerText = '📷 ' + name + ' (' + snapshot.timestamp + ')';
                            select.appendChild(opt);
                            select.value = name;
                            onRoundChange();
                        }

                        function onRoundChange() {
                            const val = document.getElementById('round-select').value;
                            currentRound = val;
                            const indicator = document.getElementById('live-indicator');
                            if (val === 'live') {
                                indicator.innerText = '🔴 LIVE STREAMING (WAL)';
                                indicator.style.background = '#0284c7';
                            } else {
                                indicator.innerText = '📷 HISTORICAL: ' + val;
                                indicator.style.background = '#475569';
                                const snap = roundsHistory.get(val);
                                if (snap) {
                                    rawNodes = snap.nodes;
                                    rawEdges = snap.edges;
                                    filterAndLayoutNodes();
                                    updateTablesAndCharts(rawNodes);
                                }
                            }
                        }

                        function clearRoundData() {
                            rawNodes = [];
                            rawEdges = [];
                            displayNodes = [];
                            displayEdges = [];
                            filterAndLayoutNodes();
                        }

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

                        // Poll live data
                        async function pollTelemetry() {
                            if (currentRound !== 'live') return;
                            try {
                                const res = await fetch('/api/graph');
                                const data = await res.json();

                                if (data.system) {
                                    const s = data.system;
                                    document.getElementById('kpi-cpu-proc').innerText = s.processCpu.toFixed(1) + '%';
                                    document.getElementById('kpi-cpu-sys').innerText = 'System CPU: ' + s.systemCpu.toFixed(1) + '%';
                                    const cpuFill = document.getElementById('kpi-cpu-proc-fill');
                                    cpuFill.style.width = Math.min(100, s.processCpu) + '%';
                                    cpuFill.className = 'progress-fill' + (s.processCpu > 80 ? ' danger' : (s.processCpu > 50 ? ' warn' : ''));

                                    document.getElementById('kpi-heap-used').innerText = s.heapUsedMb + ' MB';
                                    document.getElementById('kpi-heap-max').innerText = 'Max: ' + s.heapMaxMb + ' MB';
                                    const heapPct = s.heapMaxMb > 0 ? (s.heapUsedMb / s.heapMaxMb) * 100 : 0;
                                    const heapFill = document.getElementById('kpi-heap-fill');
                                    heapFill.style.width = Math.min(100, heapPct) + '%';
                                    heapFill.className = 'progress-fill' + (heapPct > 85 ? ' danger' : (heapPct > 65 ? ' warn' : ''));

                                    document.getElementById('kpi-non-heap').innerText = s.nonHeapUsedMb + ' MB';
                                    document.getElementById('kpi-threads').innerText = s.threadCount;
                                    document.getElementById('kpi-procs').innerText = s.processors + ' Cores';

                                    const secs = Math.floor(s.uptimeMs / 1000);
                                    const mins = Math.floor(secs / 60);
                                    document.getElementById('kpi-uptime').innerText = mins > 0 ? (mins + 'm ' + (secs % 60) + 's') : (secs + 's');

                                    const timeStr = new Date().toLocaleTimeString().split(' ')[0];
                                    timeLabels.push(timeStr);
                                    cpuData.push(s.processCpu);
                                    heapData.push(s.heapUsedMb);
                                    if (timeLabels.length > maxHistory) {
                                        timeLabels.shift();
                                        cpuData.shift();
                                        heapData.shift();
                                    }
                                    systemChart.update();
                                }

                                rawNodes = data.nodes || [];
                                rawEdges = data.edges || [];
                                document.getElementById('kpi-nodes-count').innerText = rawNodes.length + ' Nodes, ' + rawEdges.length + ' Edges';

                                filterAndLayoutNodes();
                                updateTablesAndCharts(rawNodes);
                            } catch (e) {
                                console.error(e);
                            }
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
}

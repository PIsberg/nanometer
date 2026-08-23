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
                    <title>⚡ Nanometer: Embedded APM & Topology Graph</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <style>
                        * { box-sizing: border-box; }
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0b0f19; color: #f1f5f9; margin: 0; padding: 20px; }
                        header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #1e293b; padding-bottom: 16px; margin-bottom: 20px; }
                        h1 { margin: 0; font-size: 22px; color: #38bdf8; display: flex; align-items: center; gap: 8px; }
                        .badge { background: #0284c7; color: white; padding: 4px 12px; border-radius: 9999px; font-size: 11px; font-weight: bold; letter-spacing: 0.5px; }
                        
                        .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; margin-bottom: 20px; }
                        .kpi-card { background: #131d31; border: 1px solid #1e293b; border-radius: 8px; padding: 14px; }
                        .kpi-title { font-size: 11px; text-transform: uppercase; color: #94a3b8; font-weight: 600; margin-bottom: 6px; }
                        .kpi-val { font-size: 20px; font-weight: 700; color: #38bdf8; }
                        .kpi-sub { font-size: 11px; color: #64748b; margin-top: 4px; }
                        .progress-bar { width: 100%; height: 6px; background: #1e293b; border-radius: 3px; margin-top: 8px; overflow: hidden; }
                        .progress-fill { height: 100%; background: #38bdf8; width: 0%; transition: width 0.3s ease; }
                        .progress-fill.warn { background: #f59e0b; }
                        .progress-fill.danger { background: #ef4444; }

                        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 20px; }
                        @media (max-width: 1024px) { .grid-2 { grid-template-columns: 1fr; } }
                        
                        .card { background: #131d31; border: 1px solid #1e293b; border-radius: 8px; padding: 16px; box-shadow: 0 4px 10px rgba(0,0,0,0.3); }
                        .card h2 { font-size: 15px; margin-top: 0; color: #cbd5e1; border-bottom: 1px solid #1e293b; padding-bottom: 8px; display: flex; justify-content: space-between; }
                        
                        #canvas-container { position: relative; width: 100%; height: 380px; background: #070a12; border-radius: 6px; overflow: hidden; border: 1px solid #1e293b; }
                        canvas#topology-canvas { width: 100%; height: 100%; display: block; }
                        
                        table { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 12px; }
                        th, td { padding: 8px 10px; text-align: left; border-bottom: 1px solid #1e293b; }
                        th { color: #94a3b8; font-weight: 600; }
                        .err { color: #f87171; font-weight: bold; }
                        .chart-box { position: relative; height: 260px; width: 100%; }
                    </style>
                </head>
                <body>
                    <header>
                        <div>
                            <h1>⚡ Nanometer Embedded APM</h1>
                            <div style="font-size: 12px; color: #94a3b8; margin-top: 4px;">Zero-Boilerplate Runtime Observability & Automated Topology Inference</div>
                        </div>
                        <span class="badge">LIVE TELEMETRY (WAL)</span>
                    </header>

                    <!-- System & JVM Resource Utilization Gauges -->
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
                            <div class="kpi-sub">Metaspace & CodeCache</div>
                        </div>
                        <div class="kpi-card">
                            <div class="kpi-title">Live Threads</div>
                            <div class="kpi-val" id="kpi-threads">0</div>
                            <div class="kpi-sub" id="kpi-procs">0 Processors</div>
                        </div>
                        <div class="kpi-card">
                            <div class="kpi-title">JVM Uptime</div>
                            <div class="kpi-val" id="kpi-uptime">0s</div>
                            <div class="kpi-sub">Continuous tracing</div>
                        </div>
                    </div>

                    <!-- Topology Graph & System Charts -->
                    <div class="grid-2">
                        <div class="card">
                            <h2>
                                <span>🌐 Live Method Topology DAG</span>
                                <small style="font-size:11px;color:#64748b;font-weight:normal">Auto-discovered call graph</small>
                            </h2>
                            <div id="canvas-container">
                                <canvas id="topology-canvas"></canvas>
                            </div>
                        </div>
                        <div class="card">
                            <h2>
                                <span>📈 CPU & Memory Utilization Trend</span>
                                <small style="font-size:11px;color:#64748b;font-weight:normal">Real-time (30s window)</small>
                            </h2>
                            <div class="chart-box">
                                <canvas id="system-chart"></canvas>
                            </div>
                        </div>
                    </div>

                    <!-- Latency Breakdown Chart & Method Data -->
                    <div class="grid-2">
                        <div class="card">
                            <h2>
                                <span>📊 P95 Latency by Method (ms)</span>
                                <small style="font-size:11px;color:#64748b;font-weight:normal">Top method executions</small>
                            </h2>
                            <div class="chart-box">
                                <canvas id="latency-chart"></canvas>
                            </div>
                        </div>
                        <div class="card">
                            <h2>
                                <span>📋 Method Telemetry Summary</span>
                            </h2>
                            <div style="max-height: 260px; overflow-y: auto;">
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
                        // --- Charts initialization ---
                        const maxHistory = 20;
                        const timeLabels = [];
                        const cpuData = [];
                        const heapData = [];

                        const sysCtx = document.getElementById('system-chart').getContext('2d');
                        const systemChart = new Chart(sysCtx, {
                            type: 'line',
                            data: {
                                labels: timeLabels,
                                datasets: [
                                    {
                                        label: 'Process CPU (%)',
                                        data: cpuData,
                                        borderColor: '#38bdf8',
                                        backgroundColor: 'rgba(56, 189, 248, 0.1)',
                                        yAxisID: 'yCpu',
                                        fill: true,
                                        tension: 0.3
                                    },
                                    {
                                        label: 'Heap Used (MB)',
                                        data: heapData,
                                        borderColor: '#a855f7',
                                        backgroundColor: 'rgba(168, 85, 247, 0.1)',
                                        yAxisID: 'yMem',
                                        fill: true,
                                        tension: 0.3
                                    }
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
                            data: {
                                labels: [],
                                datasets: [
                                    { label: 'p95 (ms)', data: [], backgroundColor: '#38bdf8' },
                                    { label: 'Avg (ms)', data: [], backgroundColor: '#0284c7' }
                                ]
                            },
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

                        // --- Interactive Force-Directed Topology Graph on Canvas ---
                        const canvas = document.getElementById('topology-canvas');
                        const ctx = canvas.getContext('2d');
                        let nodes = [];
                        let edges = [];
                        let animFrame;

                        function resizeCanvas() {
                            const rect = canvas.parentElement.getBoundingClientRect();
                            canvas.width = rect.width;
                            canvas.height = rect.height;
                        }
                        window.addEventListener('resize', resizeCanvas);
                        resizeCanvas();

                        function updateTopologyGraph(newNodes, newEdges) {
                            const existingMap = new Map(nodes.map(n => [n.id, n]));
                            nodes = newNodes.map((n, i) => {
                                const prev = existingMap.get(n.id);
                                const angle = (i / Math.max(1, newNodes.length)) * Math.PI * 2;
                                const radius = Math.min(canvas.width, canvas.height) * 0.35;
                                const cx = canvas.width / 2;
                                const cy = canvas.height / 2;
                                return {
                                    ...n,
                                    x: prev ? prev.x : cx + Math.cos(angle) * radius + (Math.random() * 20 - 10),
                                    y: prev ? prev.y : cy + Math.sin(angle) * radius + (Math.random() * 20 - 10),
                                    vx: prev ? prev.vx : 0,
                                    vy: prev ? prev.vy : 0
                                };
                            });
                            edges = newEdges;
                        }

                        function drawTopology() {
                            ctx.clearRect(0, 0, canvas.width, canvas.height);
                            if (nodes.length === 0) {
                                ctx.fillStyle = '#64748b';
                                ctx.font = '13px sans-serif';
                                ctx.textAlign = 'center';
                                ctx.fillText('Waiting for runtime method telemetry...', canvas.width / 2, canvas.height / 2);
                                animFrame = requestAnimationFrame(drawTopology);
                                return;
                            }

                            // Simple physics relaxation
                            const k = 0.05;
                            const repulsion = 1500;
                            for (let i = 0; i < nodes.length; i++) {
                                for (let j = i + 1; j < nodes.length; j++) {
                                    let dx = nodes[j].x - nodes[i].x;
                                    let dy = nodes[j].y - nodes[i].y;
                                    let dist = Math.hypot(dx, dy) || 1;
                                    if (dist < 180) {
                                        let force = repulsion / (dist * dist);
                                        let fx = (dx / dist) * force;
                                        let fy = (dy / dist) * force;
                                        nodes[i].x -= fx;
                                        nodes[i].y -= fy;
                                        nodes[j].x += fx;
                                        nodes[j].y += fy;
                                    }
                                }
                            }

                            // Keep in bounds
                            nodes.forEach(n => {
                                n.x = Math.max(50, Math.min(canvas.width - 50, n.x));
                                n.y = Math.max(40, Math.min(canvas.height - 40, n.y));
                            });

                            const nodeMap = new Map(nodes.map(n => [n.id, n]));

                            // Draw Edges
                            edges.forEach(e => {
                                const src = nodeMap.get(e.source);
                                const tgt = nodeMap.get(e.target);
                                if (!src || !tgt) return;

                                ctx.beginPath();
                                ctx.moveTo(src.x, src.y);
                                ctx.lineTo(tgt.x, tgt.y);
                                ctx.strokeStyle = e.errors > 0 ? '#ef4444' : '#0284c7';
                                ctx.lineWidth = Math.min(4, Math.max(1.5, Math.log10(e.calls + 1)));
                                ctx.stroke();

                                // Directed Arrow
                                const angle = Math.atan2(tgt.y - src.y, tgt.x - src.x);
                                const arrowDist = 22;
                                const ax = tgt.x - Math.cos(angle) * arrowDist;
                                const ay = tgt.y - Math.sin(angle) * arrowDist;
                                ctx.beginPath();
                                ctx.moveTo(ax, ay);
                                ctx.lineTo(ax - 8 * Math.cos(angle - Math.PI / 6), ay - 8 * Math.sin(angle - Math.PI / 6));
                                ctx.lineTo(ax - 8 * Math.cos(angle + Math.PI / 6), ay - 8 * Math.sin(angle + Math.PI / 6));
                                ctx.fillStyle = e.errors > 0 ? '#ef4444' : '#38bdf8';
                                ctx.fill();
                            });

                            // Draw Nodes
                            nodes.forEach(n => {
                                const isErr = n.isException || n.errors > 0;
                                ctx.beginPath();
                                ctx.arc(n.x, n.y, 16, 0, Math.PI * 2);
                                ctx.fillStyle = isErr ? '#ef4444' : (n.p95 > 20 ? '#f59e0b' : '#0284c7');
                                ctx.fill();
                                ctx.lineWidth = 2;
                                ctx.strokeStyle = '#ffffff';
                                ctx.stroke();

                                // Label
                                ctx.fillStyle = '#f8fafc';
                                ctx.font = '11px sans-serif';
                                ctx.textAlign = 'center';
                                ctx.fillText(n.label, n.x, n.y - 20);

                                if (n.calls > 0) {
                                    ctx.fillStyle = '#94a3b8';
                                    ctx.font = '9px sans-serif';
                                    ctx.fillText(n.p95.toFixed(1) + 'ms', n.x, n.y + 26);
                                }
                            });

                            animFrame = requestAnimationFrame(drawTopology);
                        }
                        drawTopology();

                        // --- Real-time telemetry poll ---
                        async function refresh() {
                            try {
                                const res = await fetch('/api/graph');
                                const data = await res.json();

                                // Update System KPIs
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

                                    // Push to system time-series chart
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

                                // Update Methods Table
                                const mBody = document.querySelector('#methods-table tbody');
                                const sortedNodes = [...data.nodes].sort((a, b) => b.calls - a.calls);
                                mBody.innerHTML = sortedNodes.map(n => `
                                    <tr>
                                        <td><strong>${n.label}</strong><br><small style="color:#64748b">${n.id}</small></td>
                                        <td>${n.calls}</td>
                                        <td class="${n.errors > 0 ? 'err' : ''}">${n.errors}</td>
                                        <td>${n.p95.toFixed(2)} ms</td>
                                        <td>${n.avg.toFixed(2)} ms</td>
                                    </tr>
                                `).join('');

                                // Update Latency Bar Chart (top 8 methods)
                                const topMethods = sortedNodes.filter(n => !n.isException).slice(0, 8);
                                latencyChart.data.labels = topMethods.map(n => n.label);
                                latencyChart.data.datasets[0].data = topMethods.map(n => n.p95);
                                latencyChart.data.datasets[1].data = topMethods.map(n => n.avg);
                                latencyChart.update();

                                // Update Topology DAG Graph
                                updateTopologyGraph(data.nodes || [], data.edges || []);
                            } catch (e) {
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

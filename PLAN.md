# Nanometer Implementation Plan (PLAN.md)

---

## Phase 1: Project Scaffolding & Build Configuration
- [ ] Initialize Maven project structure: `nanometer-core`, `nanometer-agent`, `nanometer-visualizer`.
- [ ] Configure `pom.xml` with shaded packaging (relocating ByteBuddy, SQLite/DuckDB) to guarantee zero host classpath collision.
- [ ] Establish Java 21+ toolchain compatibility (virtual threads & scoped values aware).

---

## Phase 2: Zero-Allocation Telemetry & Ring Buffer
- [ ] Implement `RelationalMetricEvent` record (trace ID, parent/span IDs, method dictionary ID, latency, exception).
- [ ] Implement lock-free, pre-allocated `MetricRingBuffer` (Disruptor pattern) with atomic sequence pointers.
- [ ] Implement load-shedding mechanism to drop events under extreme backpressure without blocking caller threads.

---

## Phase 3: Bytecode Interceptor & Agent Bootstrap
- [ ] Implement `AutoMetricInterceptor` with `@Origin` and `@SuperCall` bytecode hooks.
- [ ] Build `NanometerAgent` supporting both `-javaagent` command-line flag and programmatic runtime attach `Nanometer.install(String packagePrefix)`.
- [ ] Implement ThreadLocal / ScopedValue context propagation for distributed causality tracing.

---

## Phase 4: In-Memory Topology & Graph Aggregation
- [ ] Implement `GraphMetricAggregator` tracking `NodeKey` and `EdgeKey` with atomic `LongAdder` counters.
- [ ] Compute real-time rolling aggregates: call frequency, error percentages, latency histograms / percentiles ($p50, p95, p99$).
- [ ] Map causal relationships (`CALLS`, `RAISES`, `PROPAGATES`).

---

## Phase 5: Embedded Storage Engine
- [ ] Implement `MetricDatabaseFlusher` with background scheduled batch draining.
- [ ] Create embedded schema: `graph_nodes`, `graph_edges`, `edge_metrics_1m`, `execution_metrics`.
- [ ] Optimize WAL mode, indexing on timestamps and method IDs.

---

## Phase 6: Embedded Visualizer & Automated Dashboard
- [ ] Implement `GraphAutoDiscoveryEngine` mapping telemetry patterns to chart queries.
- [ ] Build zero-dependency embedded web server using JDK `com.sun.net.httpserver.HttpServer`.
- [ ] Create single-page dashboard serving:
  - Time-series throughput chart (Chart.js)
  - Exception breakdown donut chart
  - $p95$ Latency bar chart
  - Interactive DAG topology graph (Cytoscape.js)
- [ ] Expose REST API endpoint on `http://localhost:9090/api/metrics` and `/api/graph`.

---

## Phase 7: Verification & Axiom Indexing
- [ ] Index Nanometer with Axiom: `axiom scan --path C:/dev/private/nanometer`.
- [ ] Validate symbols, blast radius, and micro-sandbox patch evaluation using Axiom MCP tools.
- [ ] Run benchmark tests measuring memory consumption (< 15MB) and CPU overhead (< 0.5%).

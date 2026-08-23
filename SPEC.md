# Nanometer Specification (SPEC.md)

**Project Name**: `Nanometer`  
**Subtitle**: Zero-Dependency Embedded Observability Toolkit & Automated APM for Java Libraries & Microservices

---

## 1. System Architecture Overview

```
[ Application / Library Code ]
       │ (Method Entry / Exit / Exception)
       ▼
[ Zero-Boilerplate Interceptor (ByteBuddy / Agent) ]
       │ Captures: traceId, parentSpanId, methodId, durationNs, exceptionType
       ▼
[ Lock-Free Lossy RingBuffer / Disruptor ] ──(Async Micro-Batch)──► [ Graph & Metric Aggregator ]
                                                                             │
                                              ┌──────────────────────────────┴──────────────────────────────┐
                                              ▼                                                             ▼
                                [ Embedded Storage Engine (SQLite/DuckDB) ]                     [ In-Memory Topology DAG ]
                                              │                                                             │
                                              └──────────────────────────────┬──────────────────────────────┘
                                                                             ▼
                                                        [ Auto-Discovery & Embedded HTTP UI ]
                                                                 (localhost:9090)
```

---

## 2. Core Modules & Subsystems

### 2.1 Zero-Allocation Bytecode Interceptor
- **Package**: `nanometer.agent` / `nanometer.interceptor`
- **Mechanism**: Dynamic bytecode instrumentation using shaded ByteBuddy or JDK `java.lang.instrument`.
- **Payload**: `RelationalMetricEvent`
  - `traceId`: `long` (ThreadLocal / ScopedValue correlation)
  - `parentSpanId`: `long`
  - `currentSpanId`: `long`
  - `className`: `String` / `int` dictionary ID
  - `methodName`: `String` / `int` dictionary ID
  - `durationNs`: `long`
  - `exceptionType`: `String` (or `"NONE"`)
  - `timestamp`: `long` (epoch millis)

### 2.2 Lock-Free In-App Telemetry Transport
- **Package**: `nanometer.buffer`
- **Mechanism**: Pre-allocated circular ring buffer with atomic sequence pointers (Disruptor pattern).
- **Load Shedding**: If buffer is saturated, metrics are dropped without blocking host application execution.
- **Overhead Budget**: < 15MB RAM, < 0.5% CPU, 0 heap allocation on the hot path.

### 2.3 Real-Time Topology & Causality Graph Aggregator
- **Package**: `nanometer.graph`
- **Topology Model**:
  - **Nodes**: Methods, Controllers, DB endpoints, and Exception types.
  - **Edges**: `CALLS`, `RAISES`, `PROPAGATES` relationships.
  - **Edge Metrics**: Call throughput, error rates, p50, p95, p99 latency aggregates.

### 2.4 Local Storage Engine (WAL / Columnar)
- **Package**: `nanometer.storage`
- **Schema**:
  - `graph_nodes (node_id, class_name, method_name, node_type)`
  - `graph_edges (source_node_id, target_node_id, relationship_type)`
  - `edge_metrics_1m (timestamp, source_node_id, target_node_id, call_count, error_count, avg_duration_ms)`
  - `execution_metrics (timestamp, class_name, method_name, duration_ms, exception_type)`

### 2.5 Automated Graph Inference & Embedded Visualizer
- **Package**: `nanometer.server` & `nanometer.visualizer`
- **Engine**: Zero-config dashboard inference that automatically renders:
  1. Time-series throughput (reqs/min)
  2. Exception distribution breakdown
  3. Method latency percentiles (p95)
  4. Live Interactive Dependency Topology DAG (Cytoscape.js / D3.js)
- **Server**: Embedded JDK `com.sun.net.httpserver.HttpServer` (zero external dependencies) listening on `http://localhost:9090`.

### 2.6 Zero-Classpath Pollution for Libraries
- Shaded runtime packaging.
- Programmatic bootstrap: `Nanometer.install("com.example.mylib");`
- Java Agent flag: `-javaagent:nanometer.jar=package=com.example`.

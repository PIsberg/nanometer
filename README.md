# ⚡ Nanometer: Zero-Dependency Embedded Observability Toolkit

> **Zero-boilerplate, zero-allocation APM and automated dependency topology graph inference for Java libraries and microservices.**

---

## 🚀 Key Features

* **Zero-Boilerplate Bytecode Interception**: Automatically attaches `@Origin` & `@SuperCall` telemetry interceptors using ByteBuddy without polluting business code.
* **Zero-Allocation Hot Path**: Lock-free pre-allocated `MetricRingBuffer` (Disruptor pattern) with automatic load shedding under extreme traffic (< 50ns latency target).
* **Automated Causality & Topology Inference**: Automatically tracks execution spans across threads to build directed method caller-callee graphs and exception cascades.
* **Embedded Local Storage (WAL)**: Asynchronous micro-batching into local SQLite in WAL mode (`.nanometer/metrics.db`).
* **Zero-Config Web Dashboard**: Embedded JDK `HttpServer` (zero external dependencies) serving real-time telemetry and topology at `http://localhost:9090`.
* **Zero Classpath Pollution**: Built as a clean multi-module project (`nanometer-bom`, `nanometer-model`, `nanometer-core`, `nanometer-storage`, `nanometer-agent`, `nanometer-visualizer`, `nanometer-api`) with shaded distribution.
* **Dual Build System**: First-class build support for both **Maven** and **Gradle**.
* **AI Governance & Guardrails**: Integrated with [VibeTags](https://github.com/PIsberg/vibetags) providing tiered guardrails for both Claude (`CLAUDE.md`, `.claude/rules/*.md`) and Gemini (`GEMINI.md`).
* **Rigorous Concurrency & Architecture Testing**: Tested with [async-test-lib](https://github.com/PIsberg/async-test-lib) (virtual thread stress collision detection) and [ArchUnit](https://www.archunit.org/) (architectural layer decoupling).

---

## 📦 Modules

* `nanometer-bom`: Bill of Materials for dependency management.
* `nanometer-model`: Core immutable telemetry records (`RelationalMetricEvent`).
* `nanometer-core`: Lock-free `MetricRingBuffer`, `GraphMetricAggregator`, and `GraphAutoDiscoveryEngine`.
* `nanometer-storage`: Embedded SQLite WAL batch flusher (`MetricDatabaseFlusher`).
* `nanometer-agent`: ByteBuddy interceptor and dynamic Java agent attach (`NanometerAgent`).
* `nanometer-visualizer`: Embedded JDK HTTP server serving real-time dashboard and REST API.
* `nanometer-api`: Main bootstrap facade (`Nanometer`), shaded all-in-one JAR, and integration test suite.

---

## 🛠️ Build & Test

### Maven
```bash
mvn clean test
```

### Gradle
```bash
gradle test
```

---

## 📦 Getting Started

### 1. Programmatic Attachment (Inside a Library or Service)

```java
import nanometer.Nanometer;

public class MyLibrary {
    static {
        // Auto-instrument all classes in your package
        Nanometer.install("com.mylibrary");
        
        // Optionally launch embedded web visualizer on port 9090
        Nanometer.startVisualizer(9090);
    }
}
```

### 2. Java Agent Attachment (Without Code Changes)

```bash
java -javaagent:nanometer-api-0.1.0-all.jar=com.myapp -jar myapp.jar
```

---

## 📊 Live Embedded Visualizer

Open your browser at:
```
http://localhost:9090
```

Provides live real-time:
* **Top Method Execution Rates & P95 Latencies**
* **Dynamic Directed Topology DAG**
* **Exception Propagation Paths & Root Cause Nodes**
* **JSON REST Endpoint**: `GET http://localhost:9090/api/graph`

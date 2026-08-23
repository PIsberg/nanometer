# 📖 Nanometer User & Integration Guide

Nanometer is designed for instant drop-in telemetry with zero external configuration or infrastructure.

---

## 📦 Adding Nanometer to Your Project

### Maven
```xml
<dependency>
    <groupId>io.nanometer</groupId>
    <artifactId>nanometer-api</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle (Kotlin DSL)
```kotlin
implementation("io.nanometer:nanometer-api:0.1.0")
```

---

## 🚀 1. Programmatic Attachment

To instrument any Java library or microservice at startup:

```java
import nanometer.Nanometer;

public class Application {
    public static void main(String[] args) {
        // Automatically instrument all methods in your package
        Nanometer.install("com.mycompany.service");

        // Start embedded APM dashboard on http://localhost:9090
        Nanometer.startVisualizer(9090);

        // Your normal business application logic
        runApplication();
    }
}
```

---

## ⚡ 2. Zero-Code Java Agent Attachment

To attach Nanometer to any existing compiled Java application without modifying code:

```bash
java -javaagent:nanometer-api-0.1.0-all.jar=com.mycompany -jar my-service.jar
```

---

## 📊 3. Embedded APM Dashboard

Open your browser to view real-time live telemetry:
```
http://localhost:9090
```

### Dashboard Features:
1. **Live Method Execution Statistics**: Real-time call count, error frequency, p95 latency, and average duration.
2. **Causality Topology Table**: Caller-to-callee dependencies dynamically discovered at runtime.
3. **Exception Propagation Paths**: Root-cause exception nodes mapped directly to the originating method call.

---

## 🔗 4. JSON REST API

Nanometer provides a built-in JSON endpoint for custom dashboards, Prometheus exporters, or external scrapers:

### `GET /api/graph`

**Sample Response**:
```json
{
  "nodes": [
    {
      "id": "com.example.order.OrderService.placeOrder",
      "label": "placeOrder",
      "calls": 142,
      "errors": 4,
      "p95": 38.45,
      "avg": 24.12,
      "isException": false
    },
    {
      "id": "Exception.SecurityException",
      "label": "SecurityException",
      "calls": 4,
      "errors": 4,
      "p95": 0.00,
      "avg": 0.00,
      "isException": true
    }
  ],
  "edges": [
    {
      "source": "com.example.order.OrderService.placeOrder",
      "target": "com.example.order.PaymentClient.chargeCreditCard",
      "calls": 142,
      "errors": 4
    }
  ]
}
```

---

## 🗄️ 5. Querying the Local SQLite Database

All execution spans are asynchronously batched to `.nanometer/metrics.db`. You can inspect raw telemetry using standard SQLite tools:

```bash
sqlite3 .nanometer/metrics.db "SELECT class_name, method_name, AVG(duration_ms), count(*) FROM execution_metrics GROUP BY class_name, method_name;"
```

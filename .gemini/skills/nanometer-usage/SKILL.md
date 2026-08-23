---
name: nanometer-usage
description: Comprehensive operational guide for integrating, configuring, debugging, and operating the Nanometer zero-dependency embedded APM observability toolkit in Java applications.
---

# Nanometer Usage Skill for Gemini

Use this skill when you need to embed, configure, debug, or write tests for the Nanometer observability library.

---

## 🚀 Quick Setup

### 1. Dependencies

**Maven** (`pom.xml`):
```xml
<dependency>
    <groupId>io.nanometer</groupId>
    <artifactId>nanometer-api</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Gradle** (`build.gradle.kts`):
```kotlin
implementation("io.nanometer:nanometer-api:0.1.0")
```

---

## ⚡ Core Integration Patterns

### Pattern A: In-Process Bootstrap (Standard Application)

Call `Nanometer.install()` at application entrypoint before worker threads start:

```java
import nanometer.Nanometer;

public class Main {
    public static void main(String[] args) {
        // Automatically instruments all classes under the target package prefix
        Nanometer.install("com.example.service");

        // Optional: Launch embedded web visualizer
        Nanometer.startVisualizer(9090);

        // Run application
        new App().start();
    }
}
```

### Pattern B: External Java Agent Attach

Run with `-javaagent` on JVM launch without altering codebase:

```bash
java -javaagent:nanometer-api-0.1.0-all.jar=com.example.service -jar app.jar
```

---

## 📊 Endpoints & Verification

* **HTML Dashboard**: `http://localhost:<port>/`
* **JSON REST API**: `http://localhost:<port>/api/graph`
* **Local SQLite Store**: `.nanometer/metrics.db`

---

## 🧪 Testing Guidelines

When writing integration or unit tests involving Nanometer:

1. Clean up database before tests:
   ```java
   new File(".nanometer/metrics.db").delete();
   ```
2. Flush buffered events before assertions:
   ```java
   if (Nanometer.getDbFlusher() != null) {
       Nanometer.getDbFlusher().flushBatch();
   }
   ```
3. Shutdown gracefully in `@AfterAll`:
   ```java
   Nanometer.shutdown();
   ```

---

## 🛡️ Guardrail Rules

* **Hot-Path Zero Allocation**: Do not modify `MetricRingBuffer.offer()` to allocate objects or block threads.
* **Lock-Free Progression**: `MetricRingBuffer` uses atomic cursor manipulation. Never introduce synchronized blocks on buffer write path.
* **Shaded Relocation**: Always use the shaded artifact for distribution to prevent ByteBuddy / SQLite version collisions on host classpaths.

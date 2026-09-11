---
paths: ["**/MetricDatabaseFlusher.java"]
---

<!-- VIBETAGS-START -->
# Rules for MetricDatabaseFlusher

## Core Functionality
- **Sensitivity**: High
- **Note**: Background worker managing WAL mode SQLite transaction batches

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Thread-safe batch draining with single-thread scheduler

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: bufferDepth, droppedEventCount.
<!-- VIBETAGS-END -->

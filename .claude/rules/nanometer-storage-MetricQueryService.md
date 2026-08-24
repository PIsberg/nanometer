---
paths: ["**/MetricQueryService.java"]
---

<!-- VIBETAGS-START -->
# Rules for MetricQueryService

## Core Functionality
- **Sensitivity**: High
- **Note**: Read-only SQLite analytics query service and canned report engine

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Thread-safe JDBC connection handling

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: sql_queries_executed_total, sql_query_duration_ms. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

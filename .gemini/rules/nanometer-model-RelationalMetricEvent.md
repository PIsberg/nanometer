---
paths: ["**/RelationalMetricEvent.java"]
---

<!-- VIBETAGS-START -->
# Rules for RelationalMetricEvent

## Core Functionality
- **Sensitivity**: High
- **Note**: Core event representation for span-correlated telemetry

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Immutable telemetry record

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: duration_ns. Traces: traceId, parentSpanId, currentSpanId.
<!-- VIBETAGS-END -->

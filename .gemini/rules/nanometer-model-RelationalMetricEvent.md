---
paths: ["**/RelationalMetricEvent.java"]
---

<!-- VIBETAGS-START -->
# Rules for RelationalMetricEvent

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Immutable telemetry record

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: durationNs. Traces: traceIdHex, parentSpanId, currentSpanId.
<!-- VIBETAGS-END -->

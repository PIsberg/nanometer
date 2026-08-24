---
paths: ["**/W3CTraceContext.java"]
---

<!-- VIBETAGS-START -->
# Rules for W3CTraceContext

## Core Functionality
- **Sensitivity**: High
- **Note**: W3C Distributed Trace Context and cross-service span propagator

## Thread-Safety Guarantee
- **Strategy**: THREAD_LOCAL
- **Note**: Thread-isolated active trace context management

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: w3c_traces_propagated. Traces: traceparent_propagation. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

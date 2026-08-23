---
paths: ["**/Nanometer.java"]
---

<!-- VIBETAGS-START -->
# Rules for Nanometer

## Core Functionality
- **Sensitivity**: High
- **Note**: Main entrypoint and lifecycle manager for Nanometer embedded APM

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Thread-safe singleton lifecycle methods

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: execution_duration_ms, call_count. Traces: traceId, spanId. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

<!-- VIBETAGS-START -->
# Rules for SystemMetricsSampler

## Core Functionality
- **Sensitivity**: High
- **Note**: JVM runtime telemetry and hardware utilization sampler

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: cpu_process_percent, cpu_system_percent, heap_used_mb, thread_count. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

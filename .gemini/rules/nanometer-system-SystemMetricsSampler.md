<!-- VIBETAGS-START -->
# Rules for SystemMetricsSampler

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: processCpu, systemCpu, heapUsedMb, heapMaxMb, threadCount. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

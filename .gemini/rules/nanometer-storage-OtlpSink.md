<!-- VIBETAGS-START -->
# Rules for OtlpSink

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: otlp_batches_exported_total, otlp_batches_dropped_total. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

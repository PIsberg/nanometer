<!-- VIBETAGS-START -->
# Rules for OtlpJsonExporter

## Core Functionality
- **Sensitivity**: High
- **Note**: OpenTelemetry Protocol (OTLP) JSON serializing and HTTP exporter

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: otlp_exported_spans_total, otlp_export_duration_ms. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

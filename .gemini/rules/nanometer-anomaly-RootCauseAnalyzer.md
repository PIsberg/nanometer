<!-- VIBETAGS-START -->
# Rules for RootCauseAnalyzer

## Core Functionality
- **Sensitivity**: High
- **Note**: Automated DAG failure cascade root cause analyzer and diagnosis engine

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: rca_evaluations_total, root_causes_identified. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

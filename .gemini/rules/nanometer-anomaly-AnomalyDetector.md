<!-- VIBETAGS-START -->
# Rules for AnomalyDetector

## Core Functionality
- **Sensitivity**: High
- **Note**: Statistical 3-sigma latency anomaly and failure burst detector

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Thread-safe Welford statistic accumulation

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: anomalies_detected_total, outlier_sigma_score. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

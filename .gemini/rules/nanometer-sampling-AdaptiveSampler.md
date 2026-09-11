<!-- VIBETAGS-START -->
# Rules for AdaptiveSampler

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: Thread-safe atomic sampling and concurrent package sets

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: sampleRate, slowThresholdMs, tailSampling. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

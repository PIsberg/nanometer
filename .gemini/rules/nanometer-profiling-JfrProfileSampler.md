<!-- VIBETAGS-START -->
# Rules for JfrProfileSampler

## Core Functionality
- **Sensitivity**: High
- **Note**: On-demand execution stack frame profiler and Flamegraph tree generator

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: Concurrent hierarchical frame aggregation

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: profiled_samples_total, flamegraph_tree_depth. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

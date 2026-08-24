---
paths: ["**/AdaptiveSampler.java"]
---

<!-- VIBETAGS-START -->
# Rules for AdaptiveSampler

## Core Functionality
- **Sensitivity**: High
- **Note**: Adaptive tail sampler and dynamic package filter controller

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: Thread-safe atomic sampling and concurrent package sets

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: sampled_events_ratio, active_package_filters_count. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

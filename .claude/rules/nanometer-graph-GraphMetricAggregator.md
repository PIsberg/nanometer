---
paths: ["**/GraphMetricAggregator.java"]
---

<!-- VIBETAGS-START -->
# Rules for GraphMetricAggregator

## Core Functionality
- **Sensitivity**: High
- **Note**: Topology DAG maintaining runtime call hierarchy and error paths

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: ConcurrentHashMap and LongAdder aggregation
<!-- VIBETAGS-END -->

---
paths: ["**/GraphMetricAggregator.java"]
---

<!-- VIBETAGS-START -->
# Rules for GraphMetricAggregator

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: ConcurrentHashMap and LongAdder aggregation
<!-- VIBETAGS-END -->

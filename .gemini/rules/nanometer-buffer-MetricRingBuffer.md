---
paths: ["**/MetricRingBuffer.java"]
---

<!-- VIBETAGS-START -->
# Rules for MetricRingBuffer

## Core Functionality
- **Sensitivity**: High
- **Note**: Lock-free circular buffer utilizing atomic CAS pointers for zero-allocation telemetry

### Rules for method offer
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: Zero allocation on hot path, target latency < 50ns

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: Per-slot sequence stamps make this safe for any number of producers and consumers

### Rules for method offer
- **Policy**: ZERO_ALLOCATION
- **Rule**: Strictly limit or prevent object allocations.
<!-- VIBETAGS-END -->

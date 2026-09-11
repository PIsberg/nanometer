---
paths: ["**/AutoMetricInterceptor.java"]
---

<!-- VIBETAGS-START -->
# Rules for AutoMetricInterceptor

## Core Functionality
- **Sensitivity**: High
- **Note**: Hot-path bytecode interceptor tracking thread execution spans

## Performance Constraints
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: Minimal execution overhead, atomic thread correlation
- **Applies to**: `AutoMetricInterceptor.exitSpan(java.lang.String,java.lang.String,long,java.lang.@org.jspecify.annotations.Nullable Throwable)`, `AutoMetricInterceptor.intercept(java.lang.reflect.Method,java.util.concurrent.Callable<?>)`

## Thread-Safety Guarantee
- **Strategy**: THREAD_LOCAL
- **Note**: ThreadLocal span tracking with lock-free ring buffer dispatch
<!-- VIBETAGS-END -->

---
paths: ["**/NanometerVisualizerServer.java"]
---

<!-- VIBETAGS-START -->
# Rules for NanometerVisualizerServer

## Core Functionality
- **Sensitivity**: High
- **Note**: Embedded zero-dependency HTTP visualizer server

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Thread-safe server lifecycle management

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: http_requests_total. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

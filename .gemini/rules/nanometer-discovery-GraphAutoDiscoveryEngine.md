---
paths: ["**/GraphAutoDiscoveryEngine.java"]
---

<!-- VIBETAGS-START -->
# Rules for GraphAutoDiscoveryEngine

## Core Functionality
- **Sensitivity**: High
- **Note**: Automated chart and topology schema discovery engine

## Observability Instrumentation
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: chart_discovery_count. 

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

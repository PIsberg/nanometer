---
paths: ["**/W3CTraceContext.java"]
---

<!-- VIBETAGS-START -->
# Rules for W3CTraceContext

## Thread-Safety Guarantee
- **Strategy**: THREAD_LOCAL
- **Note**: Thread-isolated active trace context management

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

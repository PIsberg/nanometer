<!-- VIBETAGS-START -->
# Rules for JfrProfileSampler

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: Concurrent hierarchical frame aggregation

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
<!-- VIBETAGS-END -->

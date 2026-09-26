# Result maps and spec validation at boundaries

Expected failures return `{:ok …}` / `{:error …}` maps and never throw; public boundaries validate with `clojure.spec` (plus `s/fdef` state-conservation invariants in the piece domain), with spec failures transformed into `:invalid-input` error maps carrying explain-data.

## Considered Options

- **Exceptions for expected failures**: rejected — errors are values the coordinator branches on, not exceptional control flow.

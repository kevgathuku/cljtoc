# Property-based testing with test.check

Round-trip and invertibility properties (encode/decode identity, determinism, message round-trips over 100+ generated inputs per type) sit alongside example-based edge-case tests via `defspec`, so random testing finds the cases hand-written examples miss and shrinking reduces failures to minimal inputs.

## Considered Options

- **Manual random testing**: rejected — no shrinking, hand-rolled generators, less maintainable.
- **QuickCheck-via-Java / Hypothesis inspiration / spec-based generation**: rejected — test.check is Clojure-native, stable, and sufficient.

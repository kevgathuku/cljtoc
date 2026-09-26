# Hand-written recursive-descent bencode parser

Bencode is decoded with a hand-written recursive-descent parser over raw byte arrays with explicit position tracking, producing native Clojure values (byte arrays, `long`, vectors, sorted maps) and strict BEP-3 validation (no `i-0e`, no leading zeros, truncated-input errors with byte positions). Integers are 64-bit `long`, not arbitrary precision.

## Considered Options

- **Existing Java bencode library**: rejected — unidiomatic, poor error messages, extra dependency for a learning exercise.
- **Parser combinators (instaparse)**: rejected — overkill with performance overhead and weak byte-level control.
- **State machine**: rejected — more verbose and less idiomatic than recursion for nested structures.
- **BigInt integers**: rejected in implementation — `Long/parseLong` with 64-bit range is sufficient.

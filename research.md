# Bencode Parser & Torrent Metadata Extractor - Research Findings

**Date**: 2026-01-26  
**Purpose**: Research for implementing a bencode parser and torrent metadata extractor in Clojure

---

## 1. Bencode Format Specification (BEP-3)

### Decision
Implement a recursive descent parser that handles all four bencode data types (strings, integers, lists, dictionaries) with explicit position tracking for error messages and strict validation of the BEP-3 specification.

### Rationale
- **Official BEP-3 spec is unambiguous**: The BitTorrent Enhancement Proposal 3 provides clear encoding rules that are easy to implement
- **Recursive structure maps well to Clojure**: Bencode's nested nature (lists and dictionaries can contain other bencode types) aligns perfectly with Clojure's functional, recursive programming style
- **Position tracking enables useful errors**: Tracking byte position during parsing allows specific error messages ("malformed integer at position 42") rather than generic failures
- **Strict validation prevents downstream issues**: Validating edge cases during parsing (leading zeros, i-0e, key ordering) prevents cryptic errors in later stages

### Encoding Rules (from BEP-3)

**Strings**: `<length>:<string>`
- Example: `4:spam` → "spam"
- Length is base-10 ASCII decimal
- No encoding of length itself

**Integers**: `i<number>e`
- Example: `i42e` → 42, `i-3e` → -3
- Base-10 representation
- **INVALID**: `i-0e` (negative zero)
- **INVALID**: `i03e` (leading zeros) - except `i0e` is valid
- No size limitation (can be arbitrarily large)

**Lists**: `l<elements>e`
- Example: `l4:spami42ee` → ["spam", 42]
- Elements are bencoded recursively
- Can be empty: `le` → []

**Dictionaries**: `d<key><value>...e`
- Example: `d3:cow3:moo4:spam4:eggse` → {"cow": "moo", "spam": "eggs"}
- Keys MUST be strings
- Keys MUST appear in sorted order (raw byte comparison, not alphanumeric)
- Values are bencoded recursively
- Can be empty: `de` → {}

### Edge Cases to Handle

1. **Empty values**:
   - Empty string: `0:` is valid
   - Empty list: `le` is valid
   - Empty dictionary: `de` is valid

2. **Negative integers**:
   - `i-3e` is valid
   - `i-0e` is INVALID (must reject)

3. **Leading zeros**:
   - `i03e` is INVALID
   - `i0e` is valid (only exception)

4. **Large numbers**:
   - No size limit in spec - must handle beyond 64-bit
   - Use Clojure's BigInt support: `(bigint x)`

5. **Truncated data**:
   - `4:spa` (incomplete string) - must detect and report position
   - `i42` (missing 'e') - must detect
   - `l4:spam` (unclosed list) - must detect

6. **Dictionary key ordering**:
   - Some implementations require sorted keys on encode
   - On decode, should validate or at least warn if not sorted
   - Critical for info dict hash: re-encoding must preserve exact bytes

7. **Non-UTF8 byte strings**:
   - Bencode strings are byte strings, not necessarily UTF-8
   - File names may not be valid UTF-8
   - Keep as byte arrays initially, decode to strings only when needed

8. **Duplicate dictionary keys**:
   - Spec doesn't explicitly forbid, but last value should win
   - Consider warning or error for better validation

9. **Info dict hashing**:
   - CRITICAL: Must hash the exact bencode bytes of info dict
   - Cannot do decode→re-encode roundtrip (may change byte ordering)
   - Must extract substring directly from .torrent file bytes

### Error Message Best Practices
- Include byte position: "Expected 'e' at position 42, found 'x'"
- Include context: "Invalid integer encoding 'i03e' (leading zero)"
- Be specific: "Dictionary key 'zoo' appears before 'bar' (unsorted)" vs "Parse error"
- Include partial parse state if helpful: "Failed in dict key at depth 2"

### Alternatives Considered

**1. Use existing Java bencode library**
- Pros: Battle-tested, handles edge cases
- Cons: Not idiomatic Clojure, harder to customize error messages, adds dependency
- Rejected: Learning exercise, want control over parsing

**2. Parser combinator library (like instaparse)**
- Pros: Declarative grammar, automatic error reporting
- Cons: Overkill for simple format, performance overhead, harder to control byte-level details
- Rejected: Bencode is simple enough for hand-written parser

**3. State machine approach**
- Pros: Explicit state transitions, easy to reason about
- Cons: More verbose in Clojure than recursive approach, less idiomatic
- Rejected: Recursive descent is more natural for nested structures

---

## 2. SHA-1 Hashing in Clojure

### Decision
Use Java's `java.security.MessageDigest` via Clojure interop for SHA-1 hashing, operating on byte arrays extracted directly from the .torrent file bytes without decode/re-encode.

### Rationale
- **Built into JVM**: No external dependencies needed
- **Well-tested**: Java's crypto libraries are mature and performant
- **Byte array focus**: Works directly with bytes, not strings (critical for correct hashing)
- **Pure function wrapper**: Easy to wrap in pure Clojure function
- **Performance**: Native Java implementation is fast enough for typical torrent info dicts

### Implementation Approach

```clojure
(defn sha1-bytes
  "Pure function: compute SHA-1 hash of byte array. Returns 20-byte array."
  [^bytes ba]
  (let [digest (java.security.MessageDigest/getInstance "SHA-1")]
    (.digest digest ba)))

(defn bytes->hex
  "Convert byte array to hex string for display."
  [^bytes ba]
  (apply str (map #(format "%02x" %) ba)))
```

### Critical Details for Info Dict Hashing

**DO NOT decode and re-encode**:
```clojure
;; WRONG - bytes may change
(-> torrent-bytes
    decode-bencode
    :info
    encode-bencode  ; May reorder dict keys!
    sha1-bytes)

;; CORRECT - extract original bytes
(let [info-start (find-info-dict-start torrent-bytes)
      info-end (find-info-dict-end torrent-bytes info-start)
      info-bytes (Arrays/copyOfRange torrent-bytes info-start info-end)]
  (sha1-bytes info-bytes))
```

**Why this matters**: Per BEP-3, "The info-hash must be the hash of the encoded form as found in the .torrent file, which is identical to bdecoding the metainfo file, extracting the info dictionary and encoding it **if and only if** the bdecoder fully validated the input (e.g. key ordering, absence of leading zeros)."

### Keeping Hashing Pure

```clojure
;; Pure - no side effects
(defn compute-info-hash [torrent-bytes]
  (-> torrent-bytes
      extract-info-dict-bytes
      sha1-bytes))

;; NOT pure - side effects
(defn compute-info-hash-bad [torrent-bytes]
  (println "Computing hash...")  ; Side effect!
  (sha1-bytes torrent-bytes))
```

Benefits of purity:
- Easier to test (same input always produces same output)
- No hidden state or I/O
- Can be memoized if needed
- Referentially transparent

### Performance Considerations

1. **Byte array allocation**: 
   - Use `Arrays/copyOfRange` to extract info dict bytes
   - Avoid unnecessary array copies
   - For large files: info dict is typically < 100KB, so not a bottleneck

2. **Hashing performance**:
   - SHA-1 is fast (~400 MB/s on modern CPUs)
   - Info dict is small, so hash time is negligible (< 1ms)
   - No need for optimization unless profiling shows issues

3. **Avoid repeated hashing**:
   - Hash once, store in domain model
   - Don't recompute on every access

### Alternatives Considered

**1. Use Apache Commons Codec for hashing**
- Pros: Convenient utility functions
- Cons: External dependency, minimal value over Java built-in
- Rejected: Java built-in is sufficient

**2. Use buddy-core (Clojure crypto library)**
- Pros: Idiomatic Clojure API
- Cons: External dependency, may complicate build
- Rejected: Java interop is simple enough

**3. Pure Clojure SHA-1 implementation**
- Pros: No Java interop
- Cons: Significantly slower, complex to implement correctly, security risk
- Rejected: Not worth the effort

---

## 3. Property-Based Testing with test.check

### Decision
Use `org.clojure/test.check` for property-based testing of bencode parser with custom generators for bencode data structures and properties testing round-trip encoding, invertibility, and determinism.

### Rationale
- **Official Clojure library**: Well-maintained, idiomatic, integrates with clojure.test
- **Powerful shrinking**: Automatically reduces failing inputs to minimal examples
- **Compositional generators**: Easy to build complex generators from simple ones
- **Catches edge cases**: Random testing finds cases you wouldn't think to write
- **Standard in Clojure ecosystem**: Version 1.1.3 is stable and widely used

### How test.check Works

1. **Property definition**: Define a property that should hold for all inputs
2. **Generation**: Generate random inputs using generators
3. **Testing**: Run property check many times (default 100) with increasing "size"
4. **Shrinking**: If test fails, find smaller failing input
5. **Reporting**: Show both original failure and smallest failing case

### Good Properties for Bencode Testing

**1. Round-trip property (invertibility)**:
```clojure
(prop/for-all [data (gen-bencode-value)]
  (= data (-> data encode decode)))
```
- Decode then encode should give back original bytes
- Fundamental correctness check

**2. Encode is deterministic**:
```clojure
(prop/for-all [data (gen-bencode-value)]
  (= (encode data) (encode data)))
```
- Same input should always produce same output
- Ensures no hidden randomness or state

**3. Decode is inverse of encode**:
```clojure
(prop/for-all [bytes (gen-valid-bencode-bytes)]
  (= bytes (-> bytes decode encode)))
```
- For valid bencode, decode→encode should be identity on bytes
- Tests correctness of both directions

**4. Size property**:
```clojure
(prop/for-all [lst (gen/list gen-bencode-value)]
  (let [encoded (encode lst)]
    (>= (count encoded) (+ 2 (count lst)))))
```
- Encoded size should be at least "le" (2 bytes) plus content
- Sanity check on encoding

**5. Dictionary key ordering**:
```clojure
(prop/for-all [dict (gen/map gen/string-ascii gen-bencode-value)]
  (let [encoded (encode dict)
        decoded (decode encoded)]
    (= (keys decoded) (sort (keys decoded)))))
```
- Dictionary keys should be sorted after decode
- Tests sorting requirement

**6. Error detection**:
```clojure
(prop/for-all [bytes (gen-invalid-bencode)]
  (thrown? Exception (decode bytes)))
```
- Invalid bencode should throw exceptions
- Tests error handling

### Generators for Bencode Data

**Scalar generators**:
```clojure
(def gen-byte-string
  "Generate valid bencode strings (byte arrays)."
  (gen/fmap #(.getBytes % "UTF-8") gen/string-ascii))

(def gen-bencode-int
  "Generate valid bencode integers (no leading zeros, no -0)."
  (gen/such-that #(or (zero? %) (not= \0 (first (str %))))
                 gen/small-integer))
```

**Compound generators** (using `gen/recursive-gen`):
```clojure
(def gen-bencode-value
  "Generate random bencode value (recursive)."
  (gen/recursive-gen
    (fn [inner]
      (gen/one-of [(gen/list inner)                    ; Lists
                   (gen/map gen-byte-string inner)]))  ; Dicts
    (gen/one-of [gen-byte-string                       ; Strings
                 gen-bencode-int])))                    ; Ints
```

**Invalid bencode generator** (for error testing):
```clojure
(def gen-invalid-bencode
  (gen/one-of
    [(gen/fmap #(str "i" % "0e") gen/nat)           ; Leading zero: i03e
     (gen/return (byte-array [105 45 48 101]))      ; i-0e (negative zero)
     (gen/fmap #(.getBytes (str % ":abc")) 
               (gen/choose 10 99))                   ; Wrong length: 10:abc
     (gen/fmap #(.getBytes (str "i" %))             ; Missing 'e': i42
               gen/small-integer)]))
```

### Shrinking Strategies

test.check automatically shrinks failing inputs. Understanding shrinking helps write better tests:

1. **Numbers shrink toward 0**: `42` → `21` → `10` → `5` → `2` → `1` → `0`
2. **Collections shrink by**:
   - Removing elements: `[1 2 3]` → `[1 2]` → `[1]` → `[]`
   - Shrinking elements: `[42 99]` → `[42 50]` → `[21 50]` → ...
3. **Strings shrink by**:
   - Removing characters
   - Simplifying characters (toward 'a')

**Example shrinking in action**:
```clojure
;; Property: parsed list should be same length as input list
(def bad-prop
  (prop/for-all [v (gen/vector gen/small-integer)]
    (= (count v) (count (decode (encode (rest v)))))))  ; Bug: uses rest!

;; Might fail on: [5 4 2 2 2]
;; Shrinks to: [0 1]  or  [1 0]
;; Makes debugging trivial!
```

### Integration with clojure.test

Use `defspec` macro for seamless integration:

```clojure
(ns myapp.bencode-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]))

(defspec round-trip-test
  100  ; Run 100 iterations
  (prop/for-all [data (gen-bencode-value)]
    (= data (-> data encode decode))))

;; Run with: lein test
;; Or: (run-tests)
```

### Best Practices

1. **Start with simple properties**: Round-trip, determinism before complex invariants
2. **Use meaningful generator names**: `gen-valid-torrent-metadata` vs `gen1`
3. **Test error cases separately**: Invalid input should fail predictably
4. **Combine with unit tests**: Property tests find edge cases, unit tests document examples
5. **Set appropriate iteration count**: 100 for dev, 1000+ for CI
6. **Log shrinking results**: Failing shrunk input is often the real bug

### Alternatives Considered

**1. QuickCheck (Haskell) via Java**
- Pros: Original implementation, very mature
- Cons: Foreign interop, not idiomatic Clojure
- Rejected: test.check is Clojure-native

**2. Manual random testing**
- Pros: No dependency
- Cons: No shrinking, must write own generators, less maintainable
- Rejected: Reinventing the wheel poorly

**3. Hypothesis (Python) inspiration**
- Pros: Very powerful shrinking, good ergonomics
- Cons: Different language, can't use directly
- Rejected: test.check is sufficient (inspired by same ideas)

**4. clojure.spec property testing**
- Pros: Integrated with spec system
- Cons: Spec is not finalized, more complex setup
- Rejected: test.check is stable and focused

---

## Implementation Checklist

Based on research, the implementation should:

- [ ] **Parser**: Recursive descent with position tracking
- [ ] **Edge cases**: Validate i-0e, leading zeros, key ordering
- [ ] **Errors**: Include position and context in error messages
- [ ] **SHA-1**: Extract info dict bytes directly, no re-encode
- [ ] **Purity**: All functions pure (no I/O, no side effects)
- [ ] **Properties**: Round-trip, determinism, invertibility
- [ ] **Generators**: Valid/invalid bencode, recursive structures
- [ ] **Integration**: Use defspec for clojure.test integration
- [ ] **Testing**: 100+ property tests, shrinking on failures

---

## References

- [BEP-3: BitTorrent Protocol Specification](https://www.bittorrent.org/beps/bep_0003.html)
- [test.check GitHub](https://github.com/clojure/test.check)
- [test.check Introduction](https://github.com/clojure/test.check/blob/master/doc/intro.md)
- [Java MessageDigest API](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/security/MessageDigest.html)
- [QuickCheck Paper (original)](https://www.eecs.northwestern.edu/~robby/courses/395-495-2009-fall/quick.pdf)

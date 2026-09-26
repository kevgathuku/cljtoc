# Bencode Parser & Torrent Metadata

## Summary

The `dev.cljtoc.domain.bencode` and `dev.cljtoc.domain.torrent` namespaces provide a pure-functional bencode codec and `.torrent` file parser for the BitTorrent protocol. No I/O, no mutable state, no exceptions for expected failures -- all errors are returned as data maps.

### Namespaces

| Namespace | Purpose |
|-----------|---------|
| `dev.cljtoc.domain.bencode` | Encode/decode bencode, SHA-1, byte utilities, error constructors |
| `dev.cljtoc.domain.torrent` | Parse `.torrent` files, compute info hash, validate structure |

### Type Mapping

| Bencode | Clojure (`decode-bencode`) | Clojure (`decode-bencode-raw`) |
|---------|---------|---------|
| string | `String` (UTF-8) | `byte[]` (raw bytes) |
| integer | `long` | `long` |
| list | `vector` | `vector` |
| dict | `sorted-map` with string keys | `sorted-map` with string keys |

**Note:** `decode-bencode-raw` preserves binary data (like piece hashes) without UTF-8 conversion, preventing corruption of non-text fields.

---

## Bencode Format (BEP-3)

A recursive-descent parser handles all four bencode types with explicit byte-position tracking and strict validation:

**Strings**: `<length>:<string>` — e.g. `4:spam` → "spam". Length is base-10 ASCII; `0:` (empty) is valid.

**Integers**: `i<number>e` — e.g. `i42e` → 42, `i-3e` → -3. Decoded as 64-bit `long`. **Invalid**: `i-0e` (negative zero), `i03e` (leading zeros); `i0e` is the only zero form.

**Lists**: `l<elements>e`, elements bencoded recursively; `le` is valid.

**Dictionaries**: `d<key><value>...e` — keys must be strings in sorted (raw byte comparison) order; `de` is valid. Decode produces sorted maps; encode sorts keys lexicographically.

### Edge cases enforced

- Empty values (`0:`, `le`, `de`) accepted; truncated data (`4:spa`, `i42`, `l4:spam`) reported with byte position.
- Duplicate dictionary keys: last value wins.
- Non-UTF-8 byte strings kept as byte arrays; decoded to strings only when needed.

### Error messages

Parse failures return `{:error :bencode-parse-error :message … :position …}` with byte position and context (e.g. `"leading zeros in integer"`, `"unexpected end of input in list"`), never thrown exceptions.

### Alternatives considered and rejected

Existing Java bencode library (unidiomatic, poor errors, extra dependency); parser combinators like instaparse (overkill, weak byte control); state-machine approach (verbose, less idiomatic than recursion).

---

## SHA-1 and the Info Hash

SHA-1 uses Java's `java.security.MessageDigest` via interop, wrapped as a pure function over byte arrays — no extra dependency, negligible cost on small info dicts. The info hash is computed over the info dictionary's **exact original bytes** sliced from the `.torrent` file (`Arrays/copyOfRange`), never by decode-then-re-encode, since re-encoding may reorder keys and silently change the identity hash. Hash once and store it in the domain model; don't recompute per access.

### Alternatives considered and rejected

Apache Commons Codec (no value over the JDK built-in); buddy-core (extra dependency for trivial interop); pure-Clojure SHA-1 (slower, complex, security risk).

---

## Property-Based Testing

Generative round-trip tests via `test.check`/`defspec` sit alongside example-based edge-case tests: encode/decode identity, determinism, dictionary key ordering, and invalid-input rejection (asserted as error maps, not thrown exceptions). Generators cover valid values recursively plus invalid shapes (leading zeros, `i-0e`, wrong lengths, unterminated values); test.check shrinking reduces failures to minimal inputs.

### Alternatives considered and rejected

Manual random testing (no shrinking, hand-rolled generators); QuickCheck-via-Java interop, Hypothesis-style tooling, and spec-based generation (test.check is Clojure-native, stable, sufficient).

---

## Usage

### Require the namespaces

```clojure
(require '[dev.cljtoc.domain.bencode :as bencode])
(require '[dev.cljtoc.domain.torrent :as torrent])
```

### Decode bencode

```clojure
(bencode/decode-bencode (.getBytes "d4:spami42ee" "UTF-8"))
;; => {:ok {"spam" 42}}

(bencode/decode-bencode (.getBytes "li1ei2ei3ee" "UTF-8"))
;; => {:ok [1 2 3]}

(bencode/decode-bencode (.getBytes "4:spam" "UTF-8"))
;; => {:ok "spam"}

;; Error case
(bencode/decode-bencode (.getBytes "i03e" "UTF-8"))
;; => {:error :bencode-parse-error, :message "leading zeros in integer", :position 0}
```

### Decode bencode (raw, preserving binary data)

Use `decode-bencode-raw` when you need lossless handling of binary data:

```clojure
(bencode/decode-bencode-raw (.getBytes "4:spam" "UTF-8"))
;; => {:ok #<byte[] ...>}  ; byte array, not string

;; This is used internally by the torrent parser to preserve binary piece hashes
```

### Encode bencode

```clojure
(String. (bencode/encode-bencode {"foo" 42 "bar" [1 2 3]}) "UTF-8")
;; => "d3:barli1ei2ei3ee3:fooi42ee"

(String. (bencode/encode-bencode "hello") "UTF-8")
;; => "5:hello"

(String. (bencode/encode-bencode [1 "two" 3]) "UTF-8")
;; => "li1e3:twoi3ee"
```

Dict keys are always sorted lexicographically in the output.

### Round-trip verification

```clojure
(bencode/bencode-roundtrip? {"key" [1 2 3]})
;; => true
```

### Inspect bencode type at a position

```clojure
(bencode/bencode-type (.getBytes "i42e" "UTF-8") 0)
;; => :integer

(bencode/bencode-type (.getBytes "d3:fooi1ee" "UTF-8") 0)
;; => :dict
```

### Parse a .torrent file

```clojure
(let [torrent-bytes (-> "path/to/file.torrent" clojure.java.io/file
                         java.nio.file.Files/readAllBytes)]
  (torrent/parse-torrent torrent-bytes))
;; => {:ok {:announce "http://tracker.example.com/announce"
;;          :announce-list [["http://tier1.com"] ["http://tier2.com"]]
;;          :info {:name "my-file.txt"
;;                 :piece-length 262144
;;                 :pieces [<byte-array> <byte-array> ...]
;;                 :length 1048576}
;;          :info-hash <20-byte-array>
;;          :comment "uploaded by example"
;;          :created-by "qBittorrent"
;;          :creation-date 1234567890
;;          :encoding "UTF-8"}}
```

For multi-file torrents, `:info` contains `:files` instead of `:length`:

```clojure
{:info {:name "my-directory"
        :piece-length 262144
        :pieces [...]
        :files [{:path ["subdir" "file1.txt"] :length 100}
                {:path ["subdir" "file2.txt"] :length 200}]}}
```

### Compute info hash separately

```clojure
(let [torrent-bytes (...)
      result (torrent/compute-info-hash torrent-bytes)]
  (when (:ok result)
    (bencode/bytes->hex-string (:ok result))))
;; => "a1b2c3d4e5f6..."
```

The info hash is always computed from the original bencoded bytes of the info dict, never from a re-encoded version.

### Validate a parsed torrent

```clojure
(torrent/validate-torrent parsed-torrent)
;; => {:ok true}

(torrent/validate-torrent {:info {:piece-length 0}})
;; => {:error [{:error :invalid-torrent, :message "missing required field: announce", ...}
;;             {:error :invalid-torrent, :message "piece-length must be a positive integer, got: 0", ...}
;;             ...]}
```

Validation is automatically run inside `parse-torrent`. Use `validate-torrent` directly only if you are constructing torrent maps by hand.

### Command-line interface

The CLI uses a namespace-based command structure for better organization and extensibility:

```bash
# Parse and display torrent metadata
lein run torrent.parse path/to/file.torrent

# Show available commands
lein run
```

**Available commands:**

| Command | Description | Status |
|---------|-------------|--------|
| `torrent.parse` | Parse and display torrent file metadata | ✅ Implemented |
| `torrent.download` | Download files from a torrent | 🚧 Not yet implemented |
| `torrent.seed` | Seed a torrent | 🚧 Not yet implemented |

**Example:** Parse a torrent file and print its metadata in a readable format:

```bash
lein run torrent.parse tmp/example.torrent
```

Output:

```clojure
{:announce "http://tracker.example.com/announce",
 :announce-list [["http://tracker1.com"] ["http://tracker2.com"]],
 :info {:name "example-file.txt",
        :piece-length 262144,
        :pieces "42 pieces",
        :length 11010048},
 :info-hash "a1b2c3d4e5f6789...",
 :comment "Example torrent file",
 :created-by "qBittorrent",
 :creation-date 1234567890}
```

---

## Error Handling

All functions return either `{:ok value}` on success or an error map on failure. There are two error types:

**Bencode parse errors** -- returned by decoder functions:

```clojure
{:error :bencode-parse-error
 :message "unexpected end of input in list"
 :position 7}
```

**Torrent validation errors** -- returned by torrent parser and validators:

```clojure
{:error :invalid-torrent
 :message "missing required field: info.name"
 :context {}}
```

`validate-torrent` aggregates all validation failures into `{:error [error-map ...]}`.

Check for errors with `(:error result)`:

```clojure
(let [result (bencode/decode-bencode some-bytes)]
  (if (:error result)
    (println "Parse failed:" (:message result))
    (println "Decoded:" (:ok result))))
```

---

## Public API Reference

### `dev.cljtoc.domain.bencode`

| Function | Signature | Description |
|----------|-----------|-------------|
| `decode-bencode` | `[^bytes bs]` | Decode bencode bytes to Clojure data (strings converted to UTF-8) |
| `decode-bencode-raw` | `[^bytes bs]` | Decode bencode bytes preserving binary data (strings remain as byte arrays) |
| `encode-bencode` | `[value]` | Encode Clojure data to bencode bytes |
| `bencode-type` | `[^bytes bs pos]` | Peek type at byte position |
| `bencode-roundtrip?` | `[value]` | Check encode/decode identity |
| `find-dict-value-span` | `[^bytes bs key-str]` | Find byte range of a dict value by key |
| `sha1-hash` | `[^bytes bs]` | SHA-1 hash (returns 20 bytes) |
| `bytes->hex-string` | `[^bytes bs]` | Byte array to lowercase hex string |
| `bencode-error` | `[message position]` | Construct parse error map |
| `torrent-error` | `[message context]` | Construct torrent error map |

### `dev.cljtoc.domain.torrent`

| Function | Signature | Description |
|----------|-----------|-------------|
| `parse-torrent` | `[^bytes torrent-bytes]` | Full `.torrent` parser with validation |
| `compute-info-hash` | `[^bytes torrent-bytes]` | SHA-1 of original bencoded info dict |
| `extract-info-dict-bytes` | `[^bytes torrent-bytes]` | Raw bytes of info dict |
| `extract-announce-urls` | `[decoded-dict]` | Announce URL and tier list |
| `parse-info-dict` | `[info-map]` | Parse decoded info dict to structured map |
| `parse-pieces` | `[^bytes piece-data]` | Split concatenated hashes into 20-byte chunks |
| `validate-torrent` | `[torrent]` | Run all validations |
| `validate-required-fields` | `[torrent]` | Check required fields present |
| `validate-field-types` | `[torrent]` | Check field types correct |
| `validate-piece-length` | `[torrent]` | Check piece-length is positive |
| `validate-pieces-length` | `[torrent]` | Check each piece hash is 20 bytes |

---

## Running Tests

```bash
lein test
```

The suite includes generative round-trip tests via `test.check` alongside example-based edge cases.

---

## References

- [BEP-3: BitTorrent Protocol Specification](https://www.bittorrent.org/beps/bep_0003.html)
- [test.check GitHub](https://github.com/clojure/test.check)
- [Java MessageDigest API](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/security/MessageDigest.html)

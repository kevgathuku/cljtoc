# Bencode parser API contracts

**Namespace**: `cljtoc.domain.bencode`

All functions are pure - they take data as input and return data as output, with no side effects.

---

## Bencode decoder

### `decode-bencode`

Decode bencoded byte array to Clojure data structure.

**Signature**:
```clojure
(decode-bencode [bytes])
  → {:ok value} | {:error error-map}

bytes: byte-array - Raw bencode data
value: Any - Decoded Clojure data (string, long, vector, map)
error-map: {:error keyword, :message string, :position int}
```

**Returns**:
- Success: `{:ok value}` where value is decoded data
- Failure: `{:error :bencode-parse-error :message "..." :position N}`

**Examples**:
```clojure
(decode-bencode (bytes "4:spam"))
;; => {:ok "spam"}

(decode-bencode (bytes "i42e"))
;; => {:ok 42}

(decode-bencode (bytes "li1ei2ee"))
;; => {:ok [1 2]}

(decode-bencode (bytes "d3:fooi42ee"))
;; => {:ok {"foo" 42}}

(decode-bencode (bytes "4:spa"))  ;; truncated
;; => {:error :bencode-parse-error :message "Unexpected end of input" :position 2}
```

**Edge Cases**:
- Empty input → error
- Truncated input → error with position
- Invalid format → error with position
- Nested structures → recursively decoded

---

## Bencode encoder

### `encode-bencode`

Encode Clojure data structure to bencoded byte array.

**Signature**:
```clojure
(encode-bencode [value])
  → byte-array

value: Any - Clojure data to encode (string, long, vector, map)
Returns: byte-array - Bencoded representation
```

**Throws**: Exception if value contains unsupported types

**Examples**:
```clojure
(encode-bencode "spam")
;; => #bytes "4:spam"

(encode-bencode 42)
;; => #bytes "i42e"

(encode-bencode [1 2])
;; => #bytes "li1ei2ee"

(encode-bencode {"foo" 42})
;; => #bytes "d3:fooi42ee"

(encode-bencode {"b" 1 "a" 2})
;; => #bytes "d1:ai21:bi1e"  ;; keys sorted lexicographically
```

**Encoding Rules**:
- Strings → bencode byte strings
- Longs → bencode integers
- Vectors/Lists → bencode lists
- Maps → bencode dictionaries (keys sorted)
- Byte arrays → bencode byte strings (raw bytes)

---

## Bencode utilities

### `bencode-type`

Determine the type of a bencode value from raw bytes.

**Signature**:
```clojure
(bencode-type [bytes position])
  → :string | :integer | :list | :dict | :unknown

bytes: byte-array
position: int - Current position in bytes
```

**Examples**:
```clojure
(bencode-type (bytes "4:spam") 0) ;; => :string (starts with digit)
(bencode-type (bytes "i42e") 0)   ;; => :integer (starts with 'i')
(bencode-type (bytes "li1ee") 0)  ;; => :list (starts with 'l')
(bencode-type (bytes "d...e") 0)  ;; => :dict (starts with 'd')
```

---

### `bencode-roundtrip?`

Test if encode/decode is reversible for a value.

**Signature**:
```clojure
(bencode-roundtrip? [value])
  → boolean

value: Any - Value to test
Returns: true if (decode (encode value)) == value
```

**Example**:
```clojure
(bencode-roundtrip? {"foo" 42})  ;; => true
(bencode-roundtrip? [1 2 [3 4]]) ;; => true
```

---

## Torrent parser

### `parse-torrent`

Parse .torrent file bytes into TorrentMetainfo domain model.

**Signature**:
```clojure
(parse-torrent [torrent-bytes])
  → {:ok TorrentMetainfo} | {:error error-map}

torrent-bytes: byte-array - Raw .torrent file content
TorrentMetainfo: map - Structured torrent metadata
error-map: {:error keyword, :message string, :context map}
```

**Returns**:
- Success: `{:ok {:announce "..." :info {...} :info-hash #bytes ...}}`
- Failure: `{:error :invalid-torrent :message "..." :context {...}}`

**Examples**:
```clojure
(parse-torrent (slurp-bytes "example.torrent"))
;; => {:ok {:announce "http://tracker.com/announce"
;;          :info {:name "file.txt" :piece-length 262144 ...}
;;          :info-hash #bytes [20 bytes]
;;          :comment "Example"}}

(parse-torrent (bytes "d3:fooi42ee"))  ;; missing required fields
;; => {:error :invalid-torrent 
;;     :message "Missing required field 'info'"
;;     :context {:found-keys ["foo"]}}
```

---

### `compute-info-hash`

Compute SHA-1 hash of the info dictionary from .torrent bytes.

**Signature**:
```clojure
(compute-info-hash [torrent-bytes])
  → {:ok byte-array} | {:error error-map}

torrent-bytes: byte-array - Raw .torrent file content
Returns: {:ok #bytes [20 bytes]} or error
```

**Critical**: Must extract info dict bytes from original torrent file, NOT decode and re-encode.

**Examples**:
```clojure
(compute-info-hash torrent-bytes)
;; => {:ok #bytes [0xAB 0xCD ... 20 bytes total]}
```

---

### `extract-info-dict-bytes`

Extract the raw bencoded info dictionary bytes from a .torrent file.

**Signature**:
```clojure
(extract-info-dict-bytes [torrent-bytes])
  → {:ok byte-array} | {:error error-map}

torrent-bytes: byte-array - Raw .torrent file content
Returns: Raw bytes of the info dictionary (still bencoded)
```

**Purpose**: Required for correct info hash calculation. The info hash is SHA-1 of these exact bytes.

---

## Validation functions

### `validate-torrent`

Validate a TorrentMetainfo structure for consistency.

**Signature**:
```clojure
(validate-torrent [torrent-metainfo])
  → {:ok true} | {:error error-vec}

torrent-metainfo: TorrentMetainfo map
error-vec: Vector of validation error maps
```

**Checks**:
- Required fields present (announce, info)
- Info has either :length OR :files (not both)
- Pieces length is multiple of 20
- Piece length is positive
- File paths are non-empty

**Example**:
```clojure
(validate-torrent {:announce "..." :info {:name "..." :piece-length 262144 :pieces [...]}})
;; => {:ok true}

(validate-torrent {:info {:piece-length -1}})
;; => {:error [{:field "piece-length" :message "Must be positive"}
;;             {:field "announce" :message "Required field missing"}]}
```

---

## Hash utilities

### `sha1-hash`

Compute SHA-1 hash of byte array (pure function wrapper around MessageDigest).

**Signature**:
```clojure
(sha1-hash [bytes])
  → byte-array

bytes: byte-array - Data to hash
Returns: byte-array - 20-byte SHA-1 hash
```

**Example**:
```clojure
(sha1-hash (bytes "hello"))
;; => #bytes [0xAA 0xF4 0xC6 ... 20 bytes]
```

**Note**: Pure function - no side effects, deterministic output.

---

## Error codes

All errors are returned as data (no exceptions thrown for parse failures).

**Bencode Errors**:
- `:bencode-parse-error` - Malformed bencode
- `:unexpected-eof` - Input truncated
- `:invalid-type` - Unsupported data type for encoding

**Torrent Errors**:
- `:invalid-torrent` - Missing required fields or structure invalid
- `:type-mismatch` - Field has wrong type (e.g., string instead of integer)
- `:validation-error` - Data fails validation rules

---

## Type specifications (for spec.alpha or malli)

```clojure
;; Bencode value types
(s/def ::bencode-string string?)
(s/def ::bencode-bytes bytes?)
(s/def ::bencode-integer int?)
(s/def ::bencode-list (s/coll-of ::bencode-value :kind vector?))
(s/def ::bencode-dict (s/map-of string? ::bencode-value))
(s/def ::bencode-value (s/or :string ::bencode-string
                              :bytes ::bencode-bytes
                              :integer ::bencode-integer
                              :list ::bencode-list
                              :dict ::bencode-dict))

;; Torrent metainfo
(s/def ::announce string?)
(s/def ::info-hash bytes?)  ;; exactly 20 bytes
(s/def ::piece-hash bytes?) ;; exactly 20 bytes
(s/def ::piece-length pos-int?)
(s/def ::pieces (s/coll-of ::piece-hash :kind vector?))
(s/def ::name string?)
(s/def ::length pos-int?)
(s/def ::path (s/coll-of string? :kind vector? :min-count 1))
(s/def ::file-info (s/keys :req-un [::path ::length]))
(s/def ::files (s/coll-of ::file-info :kind vector?))
(s/def ::torrent-info (s/keys :req-un [::name ::piece-length ::pieces]
                              :opt-un [::length ::files ::private]))
(s/def ::torrent-metainfo (s/keys :req-un [::announce ::info ::info-hash]
                                  :opt-un [::announce-list ::comment ::created-by]))
```

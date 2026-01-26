# Data Model: Bencode Parser & Torrent Domain

**Feature**: 002-bencode-parser
**Created**: 2026-01-26
**Status**: Design

## Domain Entities

### BencodeValue

Represents any decoded bencode value. Bencode supports four data types.

**Fields**:
- Type can be: `:bencode-string`, `:bencode-integer`, `:bencode-list`, `:bencode-dict`
- Value is the Clojure representation:
  - String → `byte-array` (binary data, may not be UTF-8)
  - Integer → `Long` (64-bit signed integer)
  - List → `vector` of BencodeValues
  - Dictionary → `map` with byte-array keys and BencodeValue values

**Representation**:
```clojure
;; Bencode string "spam" -> 4:spam
{:type :bencode-string :value #bytes [115 112 97 109]}

;; Bencode integer 42 -> i42e
{:type :bencode-integer :value 42}

;; Bencode list ["spam", 42] -> l4:spami42ee
{:type :bencode-list :value [{:type :bencode-string :value ...}
                              {:type :bencode-integer :value 42}]}

;; Bencode dict {"foo": 42} -> d3:fooi42ee
{:type :bencode-dict :value {#bytes [102 111 111] {:type :bencode-integer :value 42}}}
```

**Note**: In practice, we'll use native Clojure types directly and determine type during encoding.

---

### TorrentMetainfo

Complete metadata extracted from a .torrent file.

**Fields**:
- `announce` (String) - Primary tracker URL
- `announce-list` (Vector of Vectors of Strings, optional) - Backup tracker tiers
- `info` (TorrentInfo) - The info dictionary containing piece/file data
- `info-hash` (InfoHash) - SHA-1 hash of bencoded info dict (20 bytes)
- `comment` (String, optional) - Human-readable comment
- `created-by` (String, optional) - Tool that created the torrent
- `creation-date` (Long, optional) - Unix timestamp
- `encoding` (String, optional) - Character encoding hint

**Representation**:
```clojure
{:announce "http://tracker.example.com:8080/announce"
 :announce-list [["http://tracker1.com"] 
                 ["http://tracker2.com" "http://tracker3.com"]]
 :info {:name "example.txt" ...}
 :info-hash #bytes [20 bytes of SHA-1 hash]
 :comment "Example torrent"
 :created-by "mktorrent 1.1"
 :creation-date 1640000000
 :encoding "UTF-8"}
```

---

### TorrentInfo

The 'info' dictionary from a .torrent file. Describes pieces and files.

**Fields**:
- `name` (String) - Suggested filename (single-file) or directory name (multi-file)
- `piece-length` (Long) - Bytes per piece (typically 256KB, 512KB, 1MB, etc.)
- `pieces` (Vector of byte-arrays) - SHA-1 hashes, one per piece (each 20 bytes)
- `length` (Long, single-file only) - Total file size in bytes
- `files` (Vector of FileInfo, multi-file only) - List of files in torrent
- `private` (Boolean, optional) - If true, disable DHT/PEX

**Representation (Single-File)**:
```clojure
{:name "example.txt"
 :piece-length 262144
 :pieces [#bytes [20 bytes] #bytes [20 bytes] ...]
 :length 1048576
 :private false}
```

**Representation (Multi-File)**:
```clojure
{:name "example-folder"
 :piece-length 524288
 :pieces [#bytes [20 bytes] #bytes [20 bytes] ...]
 :files [{:path ["subfolder" "file1.txt"] :length 512000}
         {:path ["file2.txt"] :length 256000}]
 :private true}
```

**Validation Rules**:
- MUST have either `length` (single-file) OR `files` (multi-file), not both
- `pieces` length MUST be multiple of 20 (each SHA-1 is exactly 20 bytes)
- `piece-length` MUST be positive integer, typically power of 2

---

### FileInfo

Individual file metadata within a multi-file torrent.

**Fields**:
- `path` (Vector of Strings) - Path components (e.g., ["dir", "subdir", "file.txt"])
- `length` (Long) - File size in bytes

**Representation**:
```clojure
{:path ["documents" "readme.txt"]
 :length 4096}
```

**Usage**: Files appear in order, concatenated to form the complete data to be hashed into pieces.

---

### InfoHash

A 20-byte SHA-1 hash that uniquely identifies a torrent.

**Type**: `byte-array` of exactly 20 bytes

**Computation**: SHA-1 hash of the **bencoded** info dictionary (exact bytes from .torrent file)

**Representation**:
```clojure
#bytes [0xAB 0xCD 0xEF ... 20 bytes total]
```

**Critical**: The info hash MUST be computed from the original bencoded bytes, not by decoding and re-encoding (re-encoding may not produce identical bytes due to dictionary key ordering variations in the input).

---

## Relationships

```
TorrentMetainfo
  ├── announce: String
  ├── announce-list: [[String]]
  ├── info: TorrentInfo
  │     ├── name: String
  │     ├── piece-length: Long
  │     ├── pieces: [byte-array]
  │     ├── length: Long (OR files)
  │     └── files: [FileInfo]
  │           ├── path: [String]
  │           └── length: Long
  ├── info-hash: byte-array (20 bytes)
  └── comment, created-by, creation-date, encoding: optional metadata
```

---

## Type Aliases (for Implementation)

For clarity in function signatures:

```clojure
(deftype Bytes byte-array)          ;; Binary data
(deftype InfoHash byte-array)        ;; Exactly 20 bytes
(deftype PieceHash byte-array)       ;; Exactly 20 bytes
```

---

## Error Types

Parsing can fail. Represent errors as data:

```clojure
{:error :bencode-parse-error
 :message "Unexpected end of input while parsing list"
 :position 42}  ;; Byte offset in input

{:error :invalid-torrent
 :message "Missing required field 'info'"
 :context {:found-keys ["announce" "comment"]}}

{:error :type-mismatch
 :message "Expected integer for 'piece length', got string"
 :field "piece length"
 :expected :integer
 :actual :string}
```

---

## State Transitions

This feature is stateless (pure functions). State transitions are data transformations:

1. **Bencode Bytes → Bencode Value**
   ```clojure
   (decode-bencode bytes) → {:type :bencode-dict :value {k1 v1, k2 v2}}
   ```

2. **Bencode Value → Torrent Metainfo**
   ```clojure
   (parse-torrent {:type :bencode-dict :value {...}}) → {:announce "..." :info {...}}
   ```

3. **Torrent Metainfo → Info Hash**
   ```clojure
   (compute-info-hash torrent-bytes) → #bytes [20 bytes]
   ```

4. **Clojure Data → Bencode Bytes**
   ```clojure
   (encode-bencode {:foo 42}) → #bytes "d3:fooi42ee"
   ```

No mutable state. Each function takes input and returns output.

---

## Validation Invariants

- Info hash is always exactly 20 bytes
- Each piece hash is exactly 20 bytes
- Piece length is positive
- Files have non-empty paths
- Dictionary keys are byte arrays (bencode strings)
- Total pieces count = ceil(total-bytes / piece-length)

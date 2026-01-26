# Feature Specification: Bencode Parser & Torrent Domain Model

**Feature Branch**: `002-bencode-parser`
**Created**: 2026-01-26
**Status**: Draft
**Parent Architecture**: [001-clojure-bittorrent-client](../001-clojure-bittorrent-client/spec.md)
**Input**: User description: "Parse .torrent files (bencode format) to domain model with pure functions. Must decode bencode strings, integers, lists, and dictionaries. Extract torrent metadata including info hash, piece hashes, file list, and tracker URLs. All functions must be pure with no I/O. Must be 100% unit testable."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Decode Valid Bencode Data (Priority: P1)

A developer provides bencode-encoded bytes and receives the decoded data structure (strings, integers, lists, or dictionaries). This is the foundation for all torrent file parsing.

**Why this priority**: Without the ability to decode bencode, nothing else in the BitTorrent client can function. This is the absolute foundation.

**Independent Test**: Can be fully tested by providing bencode byte sequences and verifying the decoded output matches expected data structures. Delivers the core parsing capability.

**Acceptance Scenarios**:

1. **Given** bencode string bytes `4:spam`, **When** decoded, **Then** returns the string "spam"
2. **Given** bencode integer bytes `i42e`, **When** decoded, **Then** returns the integer 42
3. **Given** bencode list bytes `l4:spami42ee`, **When** decoded, **Then** returns list ["spam", 42]
4. **Given** bencode dictionary bytes `d3:bar4:spam3:fooi42ee`, **When** decoded, **Then** returns map {"bar" "spam", "foo" 42}
5. **Given** nested bencode structures, **When** decoded, **Then** returns correctly nested data structures

---

### User Story 2 - Extract Torrent Metadata (Priority: P2)

A developer provides .torrent file bytes and receives a structured domain model containing all essential metadata: info hash, announce URLs, piece information, and file details.

**Why this priority**: This transforms raw bencode data into the domain model needed by the rest of the BitTorrent client. It's the bridge between parsing and business logic.

**Independent Test**: Can be tested by providing .torrent file bytes and verifying all metadata fields are correctly extracted and structured.

**Acceptance Scenarios**:

1. **Given** valid .torrent file bytes, **When** parsed to domain model, **Then** info hash is correctly computed as SHA-1 of info dictionary
2. **Given** valid .torrent file bytes, **When** parsed, **Then** announce URL(s) are extracted
3. **Given** valid .torrent file bytes, **When** parsed, **Then** piece length and piece hashes vector are extracted
4. **Given** single-file torrent bytes, **When** parsed, **Then** file name and length are extracted
5. **Given** multi-file torrent bytes, **When** parsed, **Then** directory name and file list with paths and lengths are extracted
6. **Given** torrent with optional fields (comment, created by, creation date), **When** parsed, **Then** optional fields are included in domain model

---

### User Story 3 - Validate Torrent File Structure (Priority: P3)

When parsing a .torrent file, the parser detects and reports structural issues such as missing required fields, invalid data types, or malformed bencode.

**Why this priority**: Validation improves error messages and prevents downstream failures, but the core parsing must work first.

**Independent Test**: Can be tested by providing malformed torrent files and verifying appropriate error messages are returned.

**Acceptance Scenarios**:

1. **Given** torrent file missing required 'info' dictionary, **When** parsed, **Then** returns error indicating missing 'info'
2. **Given** torrent file with invalid 'piece length' (not an integer), **When** parsed, **Then** returns error indicating type mismatch
3. **Given** torrent file with malformed bencode, **When** decoded, **Then** returns error indicating parse failure with position
4. **Given** torrent file with 'pieces' field of incorrect length, **When** parsed, **Then** returns error indicating invalid piece hash count

---

### User Story 4 - Encode Data to Bencode (Priority: P4)

A developer provides Clojure data structures and receives bencode-encoded bytes. This enables creating torrent files or protocol messages.

**Why this priority**: Encoding is needed for some protocol operations but is less critical than decoding for the initial download functionality.

**Independent Test**: Can be tested by encoding data structures and verifying the output matches expected bencode format. Round-trip encoding/decoding should be identity.

**Acceptance Scenarios**:

1. **Given** string "spam", **When** encoded to bencode, **Then** returns bytes `4:spam`
2. **Given** integer 42, **When** encoded to bencode, **Then** returns bytes `i42e`
3. **Given** list ["spam", 42], **When** encoded to bencode, **Then** returns bytes `l4:spami42ee`
4. **Given** map {"foo" 42}, **When** encoded to bencode, **Then** returns bytes with sorted dictionary keys
5. **Given** any decoded data, **When** re-encoded, **Then** round-trip produces original bytes (or equivalent)

---

### Edge Cases

- What happens when bencode data is truncated mid-structure (incomplete)?
- How does the parser handle extremely large integers (beyond 64-bit)?
- What happens with empty strings, lists, or dictionaries in bencode?
- How are negative integers handled in bencode?
- What happens when dictionary keys are not sorted (some implementations require sorting)?
- How does the parser handle non-UTF8 byte strings in torrent file names?
- What happens when the 'pieces' field length is not a multiple of 20 bytes?
- How are duplicate dictionary keys handled in bencode?
- What happens when integer encoding has leading zeros (e.g., `i042e`)?

## Requirements *(mandatory)*

### Functional Requirements

#### Bencode Decoder

- **FR-001**: System MUST decode bencode byte strings (format: `<length>:<content>`) to Clojure strings or byte arrays
- **FR-002**: System MUST decode bencode integers (format: `i<number>e`) to Clojure integers, supporting negative values
- **FR-003**: System MUST decode bencode lists (format: `l<elements>e`) to Clojure vectors, supporting nested structures
- **FR-004**: System MUST decode bencode dictionaries (format: `d<key><value>...e`) to Clojure maps, preserving key-value pairs
- **FR-005**: System MUST handle nested bencode structures of arbitrary depth
- **FR-006**: System MUST report parse errors with descriptive messages and byte position when bencode is malformed

#### Bencode Encoder

- **FR-007**: System MUST encode Clojure strings to bencode byte string format
- **FR-008**: System MUST encode Clojure integers to bencode integer format
- **FR-009**: System MUST encode Clojure vectors/sequences to bencode list format
- **FR-010**: System MUST encode Clojure maps to bencode dictionary format with lexicographically sorted keys
- **FR-011**: System MUST handle nested Clojure data structures during encoding

#### Torrent Metadata Parser

- **FR-012**: System MUST extract the 'announce' URL (primary tracker) from .torrent files
- **FR-013**: System MUST extract 'announce-list' (backup trackers) when present
- **FR-014**: System MUST compute the info hash as SHA-1 hash of the bencoded 'info' dictionary
- **FR-015**: System MUST extract 'piece length' (bytes per piece) from the info dictionary
- **FR-016**: System MUST extract 'pieces' field and split into vector of 20-byte SHA-1 hashes
- **FR-017**: System MUST extract single-file metadata (name, length) when torrent contains single file
- **FR-018**: System MUST extract multi-file metadata (directory name, file list with paths and lengths) when torrent contains multiple files
- **FR-019**: System MUST extract optional fields (comment, created by, creation date, encoding) when present
- **FR-020**: System MUST validate that required fields exist in .torrent files (announce, info, piece length, pieces, name)

#### Architecture Compliance

- **FR-021**: All parsing and encoding functions MUST be pure (no side effects, deterministic, no I/O)
- **FR-022**: All functions MUST accept data as explicit arguments (no global state access)
- **FR-023**: System MUST be 100% unit testable without file system or network access
- **FR-024**: Functions MUST return either success values or error data structures (no exceptions for parse failures)

### Key Entities

- **BencodeValue**: A decoded bencode value - can be a string (byte array), integer (long), list (vector), or dictionary (map)
- **TorrentMetainfo**: Complete metadata from .torrent file including announce URLs, info dictionary, and optional metadata
- **TorrentInfo**: The 'info' dictionary contents including piece length, pieces, files, and name
- **FileInfo**: Individual file metadata with path (vector of strings) and length (bytes)
- **InfoHash**: 20-byte SHA-1 hash uniquely identifying a torrent

## Assumptions

- Bencode strings can contain arbitrary binary data (not necessarily UTF-8 text)
- Dictionary keys in bencode are always byte strings (not integers or other types)
- The 'pieces' field is always a multiple of 20 bytes (each SHA-1 hash is 20 bytes)
- Info hash computation follows BitTorrent v1 spec (SHA-1 of bencoded info dict, not the original bytes)
- File paths in multi-file torrents are represented as lists of strings
- Bencode integers fit within 64-bit signed integer range (Java long)
- The parser should be permissive where reasonable (accept unsorted dict keys) but validate critical fields

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The bencode decoder successfully parses the official BitTorrent v1 specification example files without errors
- **SC-002**: All parsing and encoding functions achieve 100% unit test coverage with no I/O dependencies
- **SC-003**: Round-trip encoding/decoding of any valid bencode data structure produces equivalent output
- **SC-004**: The parser correctly extracts info hash from 10 real-world .torrent files, matching the expected SHA-1 values
- **SC-005**: Parse errors include descriptive messages that identify the exact issue (missing field, type mismatch, malformed bencode) and byte position when applicable
- **SC-006**: The parser handles .torrent files up to 1MB in size without memory issues (streaming or efficient parsing)
- **SC-007**: All functions are pure and produce identical output given identical input across multiple invocations


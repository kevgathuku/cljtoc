# Implementation Plan: Bencode Parser & Torrent Domain Model

**Branch**: `002-bencode-parser` | **Date**: 2026-01-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-bencode-parser/spec.md`

## Summary

Implement pure functional bencode decoder/encoder and torrent metadata parser in Clojure. All functions are pure (no I/O), deterministic, and 100% unit testable. The bencode parser handles strings, integers, lists, and dictionaries. The torrent parser extracts metadata including info hash (SHA-1), piece hashes, file lists, and tracker URLs from .torrent files. This is the foundational layer for all BitTorrent protocol operations.

## Technical Context

**Language/Version**: Clojure 1.11+ (JVM)
**Primary Dependencies**: 
  - None for bencode parsing (pure Clojure)
  - Java MessageDigest for SHA-1 hashing (standard library)
**Storage**: N/A (pure functions, no persistence)
**Testing**: clojure.test with property-based testing (test.check recommended)
**Target Platform**: JVM (cross-platform)
**Project Type**: Library module within single project
**Performance Goals**: 
  - Parse 1MB .torrent files in <100ms
  - Memory overhead <10x file size (efficient byte handling)
**Constraints**: 
  - **CRITICAL**: All functions MUST be pure (no side effects)
  - NO file I/O (callers provide bytes)
  - NO exceptions for parse failures (return error values)
  - NO global state
**Scale/Scope**: 
  - Handle .torrent files up to 10MB
  - Support deeply nested bencode structures (depth 100+)
  - Process torrents with 100,000+ pieces

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Pure Domain Layer
- ✅ **PASS**: All bencode and torrent parsing logic will be pure functions
- ✅ **PASS**: No I/O operations (bytes provided as arguments)
- ✅ **PASS**: State transitions represented as data transformations
- ✅ **PASS**: No imports of I/O or concurrency namespaces

### Principle II: Explicit Effect Boundaries  
- ✅ **PASS**: This feature has NO effects (pure parsing only)
- ✅ **PASS**: File I/O happens outside this module (callers provide bytes)
- ✅ **PASS**: SHA-1 hashing uses pure MessageDigest interface

### Principle III: Crash-Only Supervision
- ✅ **PASS**: No concurrent processes (synchronous parsing)
- ✅ **PASS**: No supervision needed (pure function library)

### Principle IV: No Hidden State
- ✅ **PASS**: No global atoms, vars, or dynamic bindings
- ✅ **PASS**: All state passed explicitly as function arguments
- ✅ **PASS**: Parser state is local to function execution

### Principle V: Testability Without I/O
- ✅ **PASS**: 100% unit testable with byte arrays
- ✅ **PASS**: No mocks needed (all functions pure)
- ✅ **PASS**: Deterministic (same input → same output)

**GATE VERDICT**: ✅ ALL GATES PASS - Proceed to Phase 0

## Project Structure

### Documentation (this feature)

```text
specs/002-bencode-parser/
├── plan.md              # This file
├── research.md          # Phase 0 output (bencode spec, SHA-1 usage, test strategies)
├── data-model.md        # Phase 1 output (domain entities)
├── quickstart.md        # Phase 1 output (usage examples)
└── checklists/
    └── requirements.md  # Validation checklist
```

### Source Code (repository root)

```text
src/
└── dev/
    └── cljtoc/
        └── domain/           # Pure domain layer
            ├── bencode.clj   # Bencode encoder/decoder
            └── torrent.clj   # Torrent metadata parser

test/
└── dev/
    └── cljtoc/
        └── domain/
            ├── bencode_test.clj      # Bencode unit tests
            ├── torrent_test.clj      # Torrent parser unit tests
            └── fixtures/
                └── torrents/         # Sample .torrent files for testing
```

**Structure Decision**: Single project structure. Bencode parsing and torrent parsing are pure domain logic with no I/O dependencies. Placed in `domain/` to clearly indicate this is the pure functional core per constitution layering.

---

## Phase 0: Research (Complete)

All research has been completed and documented in [research.md](research.md):

1. **Bencode Format (BEP-3)**: Recursive descent parser with position tracking for error messages
2. **SHA-1 Hashing**: Java MessageDigest via interop, pure function wrapper for deterministic hashing
3. **Property-Based Testing**: test.check with custom generators for round-trip validation

---

## Phase 1: Design (Complete)

Design artifacts have been created:

1. **Data Model**: [data-model.md](data-model.md) - Domain entities (BencodeValue, TorrentMetainfo, TorrentInfo, FileInfo, InfoHash)
2. **API Contracts**: [contracts/api.md](contracts/api.md) - Function signatures, error types, validation rules
3. **Quickstart**: [quickstart.md](quickstart.md) - Usage examples and common patterns

### Post-Design Constitution Re-Check

*Re-evaluating after Phase 1 design completion:*

### Principle I: Pure Domain Layer
- ✅ **PASS**: All functions in data-model and contracts are pure
- ✅ **PASS**: No I/O in any function signature
- ✅ **PASS**: State transitions are data transformations only

### Principle II: Explicit Effect Boundaries
- ✅ **PASS**: Zero effect ports needed (pure functions only)
- ✅ **PASS**: File I/O is caller's responsibility

### Principle III: Crash-Only Supervision
- ✅ **PASS**: No processes to supervise (pure functions)

### Principle IV: No Hidden State
- ✅ **PASS**: All functions accept explicit arguments
- ✅ **PASS**: No global state in design

### Principle V: Testability Without I/O
- ✅ **PASS**: 100% testable with byte arrays
- ✅ **PASS**: Property-based tests planned for round-trip validation
- ✅ **PASS**: No mocks needed (pure functions)

**POST-DESIGN VERDICT**: ✅ ALL GATES PASS - Design complies with constitution

---

## Implementation Checklist

### Core Bencode Decoder
- [ ] Parse bencode strings (length:data format)
- [ ] Parse bencode integers (i<num>e format)
- [ ] Parse bencode lists (l<items>e format)
- [ ] Parse bencode dictionaries (d<k><v>...e format)
- [ ] Handle nested structures recursively
- [ ] Error handling with position tracking
- [ ] Unit tests for all bencode types
- [ ] Property-based tests for round-trip

### Core Bencode Encoder
- [ ] Encode Clojure strings to bencode
- [ ] Encode Clojure integers to bencode
- [ ] Encode Clojure vectors to bencode lists
- [ ] Encode Clojure maps to bencode dicts (sorted keys)
- [ ] Handle nested structures recursively
- [ ] Unit tests for all data types
- [ ] Property-based tests for encode/decode identity

### Torrent Metadata Parser
- [ ] Extract announce URL
- [ ] Extract announce-list (optional)
- [ ] Extract info dictionary
- [ ] Compute info hash (SHA-1 of info dict bytes)
- [ ] Parse single-file torrents (name, length)
- [ ] Parse multi-file torrents (files list)
- [ ] Extract optional fields (comment, created-by, etc.)
- [ ] Validation of required fields
- [ ] Error handling for malformed torrents
- [ ] Unit tests with real .torrent files

### Testing Infrastructure
- [ ] Set up test fixtures (sample .torrent files)
- [ ] Property-based test generators
- [ ] Integration tests with known torrents
- [ ] Performance tests (1MB files in <100ms)

---

## Next Steps

This plan is complete. The next phase is `/speckit.tasks` to break down implementation into actionable tasks, followed by `/speckit.implement` to execute the implementation.

**Key Deliverables**:
- `src/dev/cljtoc/domain/bencode.clj` - Pure bencode encoder/decoder
- `src/dev/cljtoc/domain/torrent.clj` - Pure torrent metadata parser
- `test/dev/cljtoc/domain/bencode_test.clj` - Comprehensive test suite
- `test/dev/cljtoc/domain/torrent_test.clj` - Torrent parsing tests

**Dependencies**: None (this is the foundation feature)

**Downstream Consumers**: Features 003-006 will use this to parse .torrent files and protocol messages

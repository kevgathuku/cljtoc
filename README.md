# BitTorrent Client (Clojure)

A pure-functional BitTorrent client implementation in Clojure, built with a focus on correctness, testability, and clean architecture.

[![codecov](https://codecov.io/gh/kevgathuku/cljtoc/graph/badge.svg?token=69TNO95GSC)](https://codecov.io/gh/kevgathuku/cljtoc)

## Status

🚧 **In Development** - This is an educational/experimental project implementing the BitTorrent protocol from scratch.

### Implemented Features

- ✅ **Bencode Parser** - Full bencode encoder/decoder with comprehensive validation
- ✅ **Torrent Metadata Parser** - Parse `.torrent` files and extract metadata
- ✅ **Info Hash Computation** - SHA-1 hash computation preserving original encoding
- ✅ **CLI Interface** - Parse, download, pause, resume, status, and stop commands (`torrent.seed` not yet implemented)
- ✅ **Tracker Protocol** - HTTP and UDP tracker communication (BEP 3, BEP 15)
  - Build and parse HTTP announce requests/responses (compact + dictionary peer formats)
  - Full UDP tracker protocol: connect, announce, scrape, error (BEP 15)
  - Re-announce scheduling with exponential backoff
  - clojure.spec validation on all public functions with `s/fdef`
- ✅ **Peer Wire Protocol** - BEP 3 peer message parsing, building, and state machine
  - Parse and build all 9 message types: keep-alive, choke, unchoke, interested, not-interested, have, bitfield, request, piece, cancel
  - 68-byte handshake encode/decode with protocol validation
  - Pure peer connection state machine: `(state, message) → new-state`
  - BitSet-based bitfield with `peer-has-piece?` and `can-request?` queries
  - 16 KiB block size enforcement on request/piece/cancel
  - Generative round-trip tests for all message types
- ✅ **Piece Management** - Pure domain logic for piece tracking, selection, and verification
  - Immutable PieceState state machine: needed → in-flight → verified (with requeue)
  - Rarest-first piece selection with deterministic tie-breaking
  - 16 KiB block decomposition for peer wire protocol requests
  - SHA-1 integrity verification of assembled piece bytes
  - Endgame mode detection and duplicate requesting
  - Full clojure.spec coverage with `s/fdef` invariants
- ✅ **Download Orchestration** - End-to-end download coordination
  - Download lifecycle: start, pause, resume, stop, progress reporting
  - Injectable effect ports for network, disk, and time (with test doubles)
  - Peer worker coordination and piece verification handling
  - Persisted state with pause/resume across restarts

### Roadmap

- 🚧 Seeding (`torrent.seed`)
- 🚧 DHT (Distributed Hash Table)

## Quick Start

### Prerequisites

- [Leiningen](https://leiningen.org/) 2.9.0 or higher
- Java 11 or higher

### Installation

```bash
# Clone the repository
git clone https://github.com/kevgathuku/cljtoc.git
cd cljtoc

# Run tests to verify setup
lein test
```

### Basic Usage

#### Parse a Torrent File

```bash
lein run torrent.parse path/to/file.torrent
```

Example output:
```clojure
{:announce "http://tracker.example.com:6969/announce",
 :announce-list [["http://tracker1.com"] ["http://tracker2.com"]],
 :info {:name "example-file.txt",
        :piece-length 262144,
        :pieces "42 pieces",
        :length 11010048},
 :info-hash "a1b2c3d4e5f67890abcdef1234567890abcdef12",
 :comment "Example torrent",
 :created-by "qBittorrent",
 :creation-date 1234567890}
```

#### Show Available Commands

```bash
lein run
```

### REPL Usage

```clojure
# Start a REPL
lein repl

# Parse bencode data
(require '[dev.cljtoc.domain.bencode :as bencode])

(bencode/decode-bencode (.getBytes "d4:spami42ee" "UTF-8"))
;; => {:ok {"spam" 42}}

# Parse a torrent file
(require '[dev.cljtoc.domain.torrent :as torrent])
(require '[clojure.java.io :as io])

(def torrent-bytes
  (-> "path/to/file.torrent"
      io/file
      java.nio.file.Files/readAllBytes))

(torrent/parse-torrent torrent-bytes)
;; => {:ok {...}}
```

## Project Structure

```
torrent-client-clj/
├── src/dev/cljtoc/
│   ├── core.clj                  # CLI entry point
│   ├── cli/state.clj             # Persisted CLI download state
│   ├── utils.clj                 # Shared byte helpers (no layer dependencies)
│   ├── domain/                   # Pure torrent logic (bencode, torrent, pieces, peer-address)
│   ├── protocol/                 # Pure parsing/encoding (tracker, peer, peer-state)
│   ├── orchestration/            # Download lifecycle (download)
│   ├── coordination/             # core.async flows (peer-worker, peer-connection)
│   ├── ports/                    # Effect protocols + real implementations (network, disk, time)
│   └── test_doubles/             # In-memory ports for tests
├── test/dev/cljtoc/              # Mirrors src layout, plus integration/
├── specs/                        # Feature specifications
│   ├── 001-clojure-bittorrent-client/
│   ├── 002-bencode-parser/
│   ├── 003-tracker-protocol/
│   ├── 004-peer-wire-protocol/
│   ├── 005-piece-management/
│   └── 006-download-orchestration/
└── doc/
    └── bencode-parser.md         # Bencode API documentation
```

## Development

### Running Tests

```bash
# Run all tests
lein test

# Run tests with coverage (if configured)
lein test
```

The test suite includes:
- Property-based generative tests using test.check
- Round-trip verification tests (build → parse → verify)
- Pure domain/protocol tests with no network I/O; orchestration tests run against in-memory test doubles

### Development Workflow

This project follows a specification-driven development approach:

1. **Feature Specification** - Define requirements in `specs/XXX-feature-name/spec.md`
2. **Implementation Plan** - Create detailed plan in `specs/XXX-feature-name/plan.md`
3. **Task Breakdown** - Break down into atomic tasks in `specs/XXX-feature-name/tasks.md`
4. **TDD Implementation** - Write tests first, then implementation
5. **Documentation** - Update API docs and README

### Code Style

- Pure functional style - no side effects in domain logic
- Error handling as data - return `{:ok value}` or `{:error ...}` maps
- No exceptions for expected failures
- Comprehensive docstrings on all public functions
- Type hints for performance-critical code

## Architecture Principles

### Domain-Driven Design

- Domain logic is pure and isolated in `domain/` namespaces
- No I/O in domain functions
- All parsing/encoding is deterministic and testable

### Error Handling

All domain functions return result maps instead of throwing exceptions:

```clojure
;; Success
{:ok <value>}

;; Failure
{:error :error-type
 :message "Human-readable message"
 :context {...}}
```

### Binary Data Handling

The torrent parser uses `decode-bencode-raw` to preserve binary data (piece hashes) without UTF-8 conversion, preventing data corruption. Only known text fields are selectively converted to strings.

## Documentation

- **[Bencode Parser API](doc/bencode-parser.md)** - Detailed API documentation with examples
- **[Tracker Protocol Quickstart](specs/003-tracker-protocol/quickstart.md)** - Usage examples for all tracker functions
- **[Peer Wire Protocol API](specs/004-peer-wire-protocol/README.md)** - Full API reference for peer message parsing and state machine
- **[Piece Management Quickstart](specs/005-piece-management/quickstart.md)** - Usage examples for piece state machine, selection, blocks, and verification
- **[Feature Specs](specs/)** - Detailed feature specifications and implementation plans

## CLI Commands

| Command | Description | Status |
|---------|-------------|--------|
| `torrent.parse <file>` | Parse and display torrent metadata | ✅ Implemented |
| `torrent.download <file> [dir]` | Download files from a torrent | ✅ Implemented |
| `torrent.pause [id]` | Pause an active download | ✅ Implemented |
| `torrent.resume [id]` | Resume a paused download | ✅ Implemented |
| `torrent.status [id]` | Show download status | ✅ Implemented |
| `torrent.stop [id]` | Stop a download | ✅ Implemented |
| `torrent.seed <file>` | Seed a torrent | 🚧 Not implemented |

## Contributing

This is currently an educational/experimental project. If you find bugs or have suggestions:

1. Check existing issues
2. Create a new issue with details
3. For code contributions, follow the TDD approach used in the project

## Resources

- [BitTorrent Protocol Specification](http://www.bittorrent.org/beps/bep_0003.html)
- [Bencode Specification](https://wiki.theory.org/BitTorrentSpecification#Bencoding)
- [Unofficial BitTorrent Specification](https://wiki.theory.org/BitTorrentSpecification)

## License

Copyright © 2026

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

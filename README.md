# BitTorrent Client (Clojure)

A pure-functional BitTorrent client implementation in Clojure, built with a focus on correctness, testability, and clean architecture.

## Status

🚧 **In Development** - This is an educational/experimental project implementing the BitTorrent protocol from scratch.

### Implemented Features

- ✅ **Bencode Parser** - Full bencode encoder/decoder with comprehensive validation
- ✅ **Torrent Metadata Parser** - Parse `.torrent` files and extract metadata
- ✅ **Info Hash Computation** - SHA-1 hash computation preserving original encoding
- ✅ **CLI Interface** - Namespace-based command structure

### Roadmap

- 🚧 Peer wire protocol
- 🚧 Piece selection strategies
- 🚧 Tracker communication (HTTP & UDP)
- 🚧 DHT (Distributed Hash Table)
- 🚧 File I/O and verification

## Quick Start

### Prerequisites

- [Leiningen](https://leiningen.org/) 2.9.0 or higher
- Java 11 or higher

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/torrent-client-clj.git
cd torrent-client-clj

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
│   ├── core.clj                    # CLI entry point
│   └── domain/
│       ├── bencode.clj             # Bencode encoder/decoder
│       └── torrent.clj             # Torrent metadata parser
├── test/dev/cljtoc/
│   ├── core_test.clj
│   └── domain/
│       ├── bencode_test.clj        # Bencode tests (property-based)
│       ├── torrent_test.clj        # Torrent parser tests
│       └── fixtures/torrents/      # Test torrent files
├── specs/                          # Feature specifications
│   └── 002-bencode-parser/
│       ├── spec.md                 # Feature requirements
│       ├── plan.md                 # Implementation plan
│       ├── tasks.md                # Task breakdown
│       └── contracts/              # API contracts
└── doc/
    └── bencode-parser.md           # Detailed API documentation
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
- 37 unit tests
- 155 assertions
- Property-based tests using test.check
- Round-trip verification tests

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
- **[Feature Specs](specs/)** - Detailed feature specifications and implementation plans

## CLI Commands

| Command | Description | Status |
|---------|-------------|--------|
| `torrent.parse <file>` | Parse and display torrent metadata | ✅ Implemented |
| `torrent.download <file>` | Download files from a torrent | 🚧 Not implemented |
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

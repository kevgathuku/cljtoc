# Feature Specification: Tracker Protocol Communication

**Feature Branch**: `003-tracker-protocol`
**Created**: 2026-02-15
**Status**: Draft
**Parent Architecture**: [001-clojure-bittorrent-client](../001-clojure-bittorrent-client/spec.md)
**Input**: User description: "HTTP/UDP tracker communication for peer discovery. Implement tracker announce protocol to get peer lists from trackers. Must support both HTTP and UDP tracker protocols per BEP 3. All protocol logic must be pure functions with network I/O behind ports. Must handle tracker errors gracefully and support periodic re-announces."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Parse HTTP Tracker Responses (Priority: P1)

A developer provides raw HTTP tracker response bytes and receives a structured list of peers with their IP addresses and ports. This is the foundation for peer discovery.

**Why this priority**: Without the ability to parse tracker responses, the client cannot discover peers to connect to. This is the most critical piece of tracker communication since most trackers use HTTP.

**Independent Test**: Can be fully tested by providing HTTP tracker response bytes (bencode-encoded peer list) and verifying the parsed output contains valid peer information. Delivers immediate peer discovery capability.

**Acceptance Scenarios**:

1. **Given** HTTP tracker response with compact peer list (binary format), **When** parsed, **Then** returns list of peers with IP addresses and ports
2. **Given** HTTP tracker response with dictionary-model peer list, **When** parsed, **Then** returns list of peers with peer IDs, IPs, and ports
3. **Given** HTTP tracker response with 'interval' field, **When** parsed, **Then** interval value is extracted for re-announce scheduling
4. **Given** HTTP tracker response with 'complete' and 'incomplete' counts, **When** parsed, **Then** swarm statistics are extracted
5. **Given** HTTP tracker response with 'tracker id', **When** parsed, **Then** tracker ID is extracted for subsequent announces

---

### User Story 2 - Build HTTP Tracker Announce Requests (Priority: P2)

A developer provides torrent metadata and client state, and receives a properly formatted HTTP tracker announce request URL with all required parameters.

**Why this priority**: Once we can parse responses, we need to build requests to get those responses. This completes the HTTP tracker protocol cycle.

**Independent Test**: Can be tested by providing torrent info hash, peer ID, port, and download statistics, then verifying the generated URL contains all required BitTorrent protocol parameters.

**Acceptance Scenarios**:

1. **Given** torrent info hash, peer ID, and port, **When** building announce request, **Then** URL includes info_hash, peer_id, and port parameters
2. **Given** download statistics (uploaded, downloaded, left), **When** building announce request, **Then** URL includes uploaded, downloaded, and left parameters
3. **Given** event type (started, completed, stopped), **When** building announce request, **Then** URL includes event parameter
4. **Given** tracker URL with existing query parameters, **When** building announce request, **Then** parameters are correctly appended
5. **Given** compact mode preference, **When** building announce request, **Then** URL includes compact=1 parameter

---

### User Story 3 - Parse UDP Tracker Protocol Messages (Priority: P3)

A developer provides raw UDP tracker message bytes and receives structured data (connect responses, announce responses, error messages). This enables UDP tracker support as an alternative to HTTP.

**Why this priority**: UDP trackers are faster and more efficient than HTTP trackers, but HTTP is more common. UDP support adds robustness when HTTP trackers are unavailable.

**Independent Test**: Can be tested by providing UDP tracker message bytes and verifying the parsed output matches the UDP tracker protocol specification.

**Acceptance Scenarios**:

1. **Given** UDP connect response bytes, **When** parsed, **Then** returns connection ID and transaction ID
2. **Given** UDP announce response bytes, **When** parsed, **Then** returns interval, leechers, seeders, and peer list
3. **Given** UDP error response bytes, **When** parsed, **Then** returns error message string
4. **Given** UDP scrape response bytes, **When** parsed, **Then** returns statistics for requested torrents
5. **Given** malformed UDP message bytes, **When** parsed, **Then** returns error indicating parse failure

---

### User Story 4 - Build UDP Tracker Protocol Messages (Priority: P4)

A developer provides connection state and torrent metadata, and receives properly formatted UDP tracker protocol message bytes for connect, announce, and scrape requests.

**Why this priority**: Completes the UDP tracker protocol cycle, enabling full UDP tracker communication.

**Independent Test**: Can be tested by providing torrent metadata and verifying the generated message bytes conform to the UDP tracker protocol specification.

**Acceptance Scenarios**:

1. **Given** transaction ID, **When** building connect request, **Then** returns 16-byte message with protocol ID, action, and transaction ID
2. **Given** connection ID, info hash, and download stats, **When** building announce request, **Then** returns properly formatted announce message
3. **Given** connection ID and info hash list, **When** building scrape request, **Then** returns properly formatted scrape message
4. **Given** announce parameters, **When** building message, **Then** event codes are correctly encoded (0=none, 1=completed, 2=started, 3=stopped)

---

### User Story 5 - Handle Tracker Error Responses (Priority: P5)

When a tracker returns an error response (HTTP failure reason or UDP error), the parser detects and extracts the error message to enable appropriate retry logic.

**Why this priority**: Error handling improves robustness but basic communication must work first.

**Independent Test**: Can be tested by providing various tracker error responses and verifying error messages are correctly extracted.

**Acceptance Scenarios**:

1. **Given** HTTP tracker response with 'failure reason' field, **When** parsed, **Then** returns error with failure reason message
2. **Given** HTTP 4xx/5xx status code, **When** parsed, **Then** returns error indicating HTTP failure
3. **Given** UDP error response, **When** parsed, **Then** returns error with error message string
4. **Given** network timeout (simulated), **When** announce attempt fails, **Then** returns timeout error
5. **Given** malformed tracker response, **When** parsed, **Then** returns parse error with diagnostic information

---

### User Story 6 - Calculate Re-Announce Timing (Priority: P6)

A developer provides tracker interval and current time, and receives the next announce time. This enables periodic tracker communication for peer list updates.

**Why this priority**: Re-announcing is important for maintaining fresh peer lists, but initial announce capability is more critical.

**Independent Test**: Can be tested by providing interval values and verifying the calculated next announce time is correct.

**Acceptance Scenarios**:

1. **Given** tracker interval of 1800 seconds and current time, **When** calculating next announce, **Then** returns time 1800 seconds in future
2. **Given** tracker min_interval field, **When** calculating next announce, **Then** uses min_interval instead of interval if present
3. **Given** no interval in tracker response, **When** calculating next announce, **Then** uses default interval of 1800 seconds
4. **Given** failed announce attempt, **When** calculating retry time, **Then** returns exponential backoff schedule
5. **Given** 'started' event, **When** calculating next announce, **Then** schedules immediate re-announce after connection

---

### Edge Cases

- What happens when tracker response is missing required fields (interval, peers)?
- How does the parser handle IPv6 peers in compact format?
- What happens when info_hash or peer_id contain special characters in URL encoding?
- How are multi-tracker scenarios handled (announce-list with fallbacks)?
- What happens when UDP connection ID expires during announce sequence?
- How does the system handle tracker responses with zero peers?
- What happens when HTTP tracker returns partial/truncated response?
- How are tracker warnings (warning message field) communicated to the caller?
- What happens when tracker response includes both compact and dictionary peer lists?
- How does the parser handle tracker responses larger than expected (thousands of peers)?

## Requirements *(mandatory)*

### Functional Requirements

#### HTTP Tracker Protocol

- **FR-001**: System MUST parse HTTP tracker responses encoded in bencode format
- **FR-002**: System MUST extract peer list from tracker responses in both compact (binary) and dictionary formats
- **FR-003**: System MUST extract interval, min_interval, complete, incomplete, and tracker_id fields when present
- **FR-004**: System MUST build HTTP tracker announce request URLs with required parameters: info_hash, peer_id, port, uploaded, downloaded, left
- **FR-005**: System MUST support optional announce parameters: event (started/completed/stopped/empty), compact, no_peer_id, numwant
- **FR-006**: System MUST URL-encode binary data (info_hash, peer_id) according to BitTorrent specification
- **FR-007**: System MUST parse tracker failure responses and extract failure reason messages

#### UDP Tracker Protocol

- **FR-008**: System MUST build UDP tracker connect request messages according to BEP 15
- **FR-009**: System MUST parse UDP tracker connect response messages and extract connection ID
- **FR-010**: System MUST build UDP tracker announce request messages with connection ID, info_hash, and statistics
- **FR-011**: System MUST parse UDP tracker announce response messages and extract peer list, interval, and swarm statistics
- **FR-012**: System MUST parse UDP tracker error responses and extract error messages
- **FR-013**: System MUST support UDP tracker scrape requests and responses
- **FR-014**: System MUST handle UDP connection ID expiration (connection IDs valid for 1 minute)

#### Peer Information

- **FR-015**: System MUST parse compact peer format (6 bytes per IPv4 peer: 4 bytes IP + 2 bytes port)
- **FR-016**: System MUST parse dictionary peer format with peer_id, ip, and port fields
- **FR-017**: System MUST support IPv6 peers in compact format (18 bytes: 16 bytes IP + 2 bytes port)
- **FR-018**: System MUST validate peer IP addresses and ports are well-formed

#### Timing and Scheduling

- **FR-019**: System MUST calculate next announce time based on tracker-provided interval
- **FR-020**: System MUST use min_interval when provided, otherwise use interval field
- **FR-021**: System MUST use default interval of 1800 seconds when tracker does not provide one
- **FR-022**: System MUST calculate exponential backoff for failed announce attempts

#### Error Handling

- **FR-023**: System MUST handle malformed tracker responses without crashing
- **FR-024**: System MUST distinguish between network errors, protocol errors, and tracker errors
- **FR-025**: System MUST preserve error context (tracker URL, error message, error type) for debugging
- **FR-033**: Spec validation failures MUST be transformed into {:error ...} result maps (not exceptions)
- **FR-034**: Error results from spec failures MUST include spec explain-data for debugging while maintaining consistent error format

#### Architecture Compliance

- **FR-026**: All protocol parsing and message building MUST be pure, deterministic functions
- **FR-027**: All network I/O MUST be isolated behind injectable port interfaces
- **FR-028**: All time-related operations MUST use injectable time port (no direct access to system time)
- **FR-029**: All functions MUST return result maps ({:ok value} or {:error ...}) instead of throwing exceptions
- **FR-030**: All data entities MUST have clojure.spec definitions for validation and generative testing
- **FR-031**: Public API functions MUST validate inputs using clojure.spec at function boundaries
- **FR-032**: Spec instrumentation MUST be opt-in (enabled for development/testing, disabled in production)

### Key Entities

All entities will have corresponding clojure.spec definitions for validation and generative testing:

- **TrackerRequest**: Represents an announce request to be sent to a tracker
  - Request type (connect, announce, scrape)
  - Protocol (HTTP or UDP)
  - Required parameters (info_hash, peer_id, port, statistics)
  - Optional parameters (event, compact mode, peer count)
  - Spec: `::tracker-request` with custom generators for 20-byte binary fields

- **TrackerResponse**: Represents a parsed tracker response
  - Peer list (IP addresses and ports)
  - Swarm statistics (seeders, leechers)
  - Re-announce interval
  - Tracker ID (for HTTP trackers)
  - Connection ID (for UDP trackers)
  - Warnings or error messages
  - Spec: `::tracker-response` with discriminated union for success/failure

- **Peer**: Individual peer information from tracker
  - IP address (IPv4 or IPv6)
  - Port number
  - Peer ID (optional, not in compact format)
  - Spec: `::peer` with custom generators for valid IP addresses and ports

- **AnnounceSchedule**: Timing information for tracker communication
  - Next announce time
  - Interval duration
  - Retry backoff state (for failures)
  - Spec: `::announce-schedule` with constraints on valid timestamps and intervals

- **TrackerError**: Error information from failed tracker communication
  - Error type (network, protocol, tracker failure)
  - Error message
  - Tracker URL
  - Timestamp
  - Spec: `::tracker-error` with enumerated error types

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Parser correctly extracts peer lists from 100% of well-formed HTTP tracker responses
- **SC-002**: Parser correctly extracts peer lists from 100% of well-formed UDP tracker responses
- **SC-003**: Request builder generates valid announce URLs that are accepted by reference BitTorrent trackers
- **SC-004**: Parser handles malformed tracker responses without crashes, returning appropriate error information
- **SC-005**: All tracker protocol functions are pure with 90%+ unit test coverage requiring no network I/O
- **SC-006**: System correctly schedules re-announces according to tracker-specified intervals within 1 second accuracy
- **SC-007**: Compact peer format parser correctly handles peer lists with 1000+ peers without performance degradation
- **SC-008**: UDP protocol correctly handles connection ID expiration and re-connection sequences
- **SC-009**: URL encoding for binary data (info_hash, peer_id) matches reference BitTorrent client implementations
- **SC-010**: Error responses from trackers are correctly distinguished from network failures with appropriate error messages
- **SC-011**: All data entities have clojure.spec definitions that enable generative testing
- **SC-012**: Generative tests successfully discover edge cases in protocol parsing and message building
- **SC-013**: Public API functions validate inputs at boundaries using clojure.spec with clear error messages

### Testing Strategy

The implementation will use clojure.spec for:

1. **Data Contract Documentation**: All entities (TrackerRequest, TrackerResponse, Peer, etc.) have explicit spec definitions serving as executable documentation

2. **Generative Testing**: Property-based tests using `clojure.spec.gen` with realistic protocol-aware generators:
   - **20-byte binary fields**: Exactly 20 bytes for info-hash and peer-id (not arbitrary length)
   - **IP addresses**: Valid IPv4 (4 bytes) and IPv6 (16 bytes) addresses in proper formats
   - **Port numbers**: Realistic range 1024-65535 (standard non-privileged ports)
   - **Compact peer format**: Properly structured byte arrays (6 bytes per IPv4 peer, 18 per IPv6)
   - **Event codes**: Valid enumeration (0=none, 1=completed, 2=started, 3=stopped)
   - **Transaction IDs**: 32-bit unsigned integers (0 to 2^32-1)
   - **Intervals**: Positive integers representing seconds (reasonable range: 60-7200)
   - Round-trip encode/decode verification for all message formats

3. **Runtime Validation**: Input validation at public API boundaries only:
   - Enabled during development and testing via `clojure.spec.test/instrument`
   - Disabled in production for performance
   - Clear, actionable error messages for invalid inputs
   - Spec failures transformed to standard `{:error :invalid-input :message "..." :spec-explain ...}` format
   - No exceptions thrown - maintains pure functional error handling (FR-029)
   - Spec explain-data included in error context for debugging

4. **Test Coverage**: Combination of example-based and generative tests to achieve 90%+ coverage without network I/O

## Clarifications

### Session 2026-02-15

- Q: Data validation strategy with clojure.spec? → A: Use clojure.spec for all data contracts with runtime validation at public API boundaries only, opt-in instrumentation for dev/test
- Q: Generative testing strategy? → A: Use spec-based generative testing for all protocol functions, with custom generators for binary data (info-hash, peer-id, compact peers)
- Q: Custom generator scope? → A: Realistic generators with protocol constraints (20-byte hashes, valid IPs, realistic port ranges 1024-65535, proper binary formats)
- Q: Spec failure error message format? → A: Map spec failures to {:error ...} result format with explain-data for debugging context
- Q: Spec namespace organization? → A: Separate spec namespace (dev.cljtoc.protocol.tracker.spec) for all specs, imported by implementation and test namespaces

## Implementation Structure

### Namespace Organization

The implementation will use a separate spec namespace for data contracts:

- **dev.cljtoc.protocol.tracker.spec**: All clojure.spec definitions for data entities
  - Entity specs: `::tracker-request`, `::tracker-response`, `::peer`, `::announce-schedule`, `::tracker-error`
  - Field specs: `::info-hash`, `::peer-id`, `::port`, `::ip-address`, etc.
  - Custom generators for protocol-aware test data
  - Imported by both implementation and test namespaces

- **dev.cljtoc.protocol.tracker**: Main implementation namespace
  - Requires `dev.cljtoc.protocol.tracker.spec` for validation
  - Public API functions validate inputs at boundaries using specs
  - Pure protocol parsing and message-building functions

- **dev.cljtoc.protocol.tracker-test**: Test namespace
  - Requires `dev.cljtoc.protocol.tracker.spec` for generative testing
  - Uses `clojure.spec.test/check` for property-based tests
  - Example-based unit tests with hardcoded fixtures
  - Achieves 90%+ coverage without network I/O

This separation allows:
- Specs to serve as executable documentation independent of implementation
- Reusability across features (future features can reference tracker specs)
- Independent control of spec validation (enable/disable without touching protocol logic)

## Dependencies

- **002-bencode-parser**: Required for parsing HTTP tracker responses (bencode format)
- **clojure.spec.alpha**: Required for data validation and generative testing

## Assumptions

- Trackers follow BEP 3 (BitTorrent Protocol) and BEP 15 (UDP Tracker Protocol) specifications
- HTTP tracker responses use bencode encoding as specified in BEP 3
- Compact peer format is preferred for efficiency (6 bytes per IPv4 peer)
- UDP trackers use port 80 or announce URL specifies correct UDP port
- Connection IDs for UDP trackers expire after 60 seconds as per BEP 15
- Default announce interval is 30 minutes (1800 seconds) when not specified by tracker
- IPv4 is required; IPv6 support is optional but included for completeness
- Tracker announce-list is processed in order with fallback to next tracker on failure
- Network I/O port provides timeout mechanism for tracker requests
- Time port provides current time for scheduling calculations
- All binary data uses big-endian byte order (network byte order)
- clojure.spec.alpha is available for data validation and generative testing
- Spec instrumentation can be toggled independently (enabled in dev/test, disabled in production)
- Custom spec generators produce realistic protocol-compliant test data

# Research: Tracker Protocol Implementation

**Date**: 2026-02-15
**Feature**: 003-tracker-protocol

## Overview

This document captures research findings for implementing BitTorrent tracker protocols (HTTP and UDP) in Clojure, focusing on protocol specifications, encoding requirements, and pure function design patterns.

## Research Areas

### 1. HTTP Tracker Protocol (BEP 3)

**Decision**: Implement HTTP tracker announce protocol per BitTorrent Enhancement Proposal 3 (BEP 3)

**Rationale**:
- BEP 3 is the foundational BitTorrent specification
- HTTP trackers are the most common tracker type in the BitTorrent ecosystem
- Well-documented with reference implementations available

**Key Findings**:

**Announce Request Parameters** (query string):
- `info_hash`: 20-byte SHA-1 hash of the info dictionary (URL-encoded)
- `peer_id`: 20-byte client identifier (URL-encoded)
- `port`: TCP port the peer is listening on (integer)
- `uploaded`: Total bytes uploaded this session (integer)
- `downloaded`: Total bytes downloaded this session (integer)
- `left`: Number of bytes left to download (integer)
- `compact`: Whether to use compact peer format (1 or 0)
- `event`: Optional event type (started, completed, stopped, empty)
- `numwant`: Optional number of peers wanted (default 50)
- `no_peer_id`: Optional flag to omit peer IDs in response

**URL Encoding Rules**:
- Binary data (info_hash, peer_id) MUST be percent-encoded
- Allowed characters: `A-Z`, `a-z`, `0-9`, `.`, `-`, `_`, `~`
- All other bytes encoded as `%XX` where XX is hexadecimal
- Example: byte 0x12 → `%12`, byte 0xFF → `%FF`

**Response Format** (bencode dictionary):
- Success response fields:
  - `interval`: Seconds to wait before next announce (integer)
  - `min interval`: (optional) Minimum announce interval (integer)
  - `tracker id`: (optional) Tracker ID for subsequent announces (string)
  - `complete`: Number of seeders (peers with complete file)
  - `incomplete`: Number of leechers (peers downloading)
  - `peers`: Peer list (compact binary or list of dictionaries)

- Failure response fields:
  - `failure reason`: Human-readable error message (string)

**Compact Peer Format**:
- 6 bytes per IPv4 peer: 4-byte IP address + 2-byte port (big-endian)
- 18 bytes per IPv6 peer: 16-byte IP address + 2-byte port (big-endian)
- More efficient than dictionary format (saves ~80% bandwidth)
- Example: IP 192.168.1.1:6881 → `[0xC0 0xA8 0x01 0x01 0x1A 0xE1]`

**Dictionary Peer Format** (legacy):
- List of dictionaries with keys:
  - `peer id`: 20-byte peer identifier (optional)
  - `ip`: IP address as string (e.g., "192.168.1.1")
  - `port`: Port number as integer

**References**:
- BEP 3: http://www.bittorrent.org/beps/bep_0003.html
- URL encoding: https://www.rfc-editor.org/rfc/rfc3986#section-2.1

---

### 2. UDP Tracker Protocol (BEP 15)

**Decision**: Implement UDP tracker protocol per BitTorrent Enhancement Proposal 15 (BEP 15)

**Rationale**:
- UDP trackers offer lower latency and reduced overhead vs HTTP
- No TCP connection setup required
- Common in modern BitTorrent deployments
- Fallback option when HTTP trackers are unreachable

**Key Findings**:

**Connection Flow**:
1. Send connect request to get connection ID
2. Use connection ID for announce/scrape requests
3. Connection ID expires after 60 seconds (must reconnect)

**Connect Request** (16 bytes):
```
Offset  Size            Name            Value
0       64-bit integer  protocol_id     0x41727101980 (magic constant)
8       32-bit integer  action          0 (connect)
12      32-bit integer  transaction_id  Random
```

**Connect Response** (16 bytes):
```
Offset  Size            Name            Value
0       32-bit integer  action          0 (connect)
4       32-bit integer  transaction_id  Must match request
8       64-bit integer  connection_id   Use for subsequent requests
```

**Announce Request** (98 bytes minimum):
```
Offset  Size            Name            Value
0       64-bit integer  connection_id   From connect response
8       32-bit integer  action          1 (announce)
12      32-bit integer  transaction_id  Random
16      20 bytes        info_hash       Torrent identifier
36      20 bytes        peer_id         Client identifier
56      64-bit integer  downloaded      Bytes downloaded
64      64-bit integer  left            Bytes remaining
72      64-bit integer  uploaded        Bytes uploaded
80      32-bit integer  event           0=none, 1=completed, 2=started, 3=stopped
84      32-bit integer  IP address      0 = default (use sender IP)
88      32-bit integer  key             Random (for IP changes)
92      32-bit integer  num_want        -1 = default (typically 50)
96      16-bit integer  port            Listening port
```

**Announce Response** (20 bytes + N*6 bytes for peers):
```
Offset  Size            Name            Value
0       32-bit integer  action          1 (announce)
4       32-bit integer  transaction_id  Must match request
8       32-bit integer  interval        Announce interval (seconds)
12      32-bit integer  leechers        Number of incomplete peers
16      32-bit integer  seeders         Number of complete peers
20      N*6 bytes       peers           Compact format (IP:port pairs)
```

**Error Response** (8 bytes + error string):
```
Offset  Size            Name            Value
0       32-bit integer  action          3 (error)
4       32-bit integer  transaction_id  Must match request
8       string          error_message   Human-readable error
```

**Important Notes**:
- All integers are big-endian (network byte order)
- Transaction ID must be random to prevent spoofing
- Connection ID expires after 60 seconds of inactivity
- UDP provides no reliability (implement timeouts and retries at higher layer)

**References**:
- BEP 15: http://www.bittorrent.org/beps/bep_0015.html

---

### 3. URL Encoding for Binary Data (Clojure)

**Decision**: Use custom URL encoding that preserves binary data integrity

**Rationale**:
- Standard `URLEncoder` in Java uses `application/x-www-form-urlencoded` (spaces as `+`)
- BitTorrent trackers expect RFC 3986 percent-encoding (spaces as `%20`)
- Info hash and peer ID are binary data (not UTF-8 text)

**Implementation Approach**:

```clojure
(defn url-encode-binary
  "URL-encode binary data per RFC 3986.
  Encodes all bytes except: A-Z a-z 0-9 . - _ ~"
  [^bytes data]
  (let [unreserved? (fn [b]
                      (or (and (>= b 0x41) (<= b 0x5A))  ; A-Z
                          (and (>= b 0x61) (<= b 0x7A))  ; a-z
                          (and (>= b 0x30) (<= b 0x39))  ; 0-9
                          (#{0x2E 0x2D 0x5F 0x7E} b)))]  ; . - _ ~
    (apply str
           (map (fn [b]
                  (if (unreserved? b)
                    (char b)
                    (format "%%%02X" b)))
                data))))
```

**Test Cases**:
- Empty bytes: `[]` → `""`
- Simple text: `[0x61 0x62 0x63]` ("abc") → `"abc"`
- Binary with special chars: `[0x12 0x34 0xFF]` → `"%12%34%FF"`
- Info hash (20 bytes): All bytes percent-encoded except unreserved

**Alternatives Considered**:
- Java `URLEncoder.encode()`: Rejected (uses `+` for spaces, not `%20`)
- Manual string manipulation: Rejected (error-prone for binary data)
- Existing library (ring.util.codec): Could be used, but simple enough to implement

---

### 4. Binary Data Parsing (Clojure)

**Decision**: Use Java interop with `ByteBuffer` for parsing binary protocol messages

**Rationale**:
- UDP tracker protocol uses fixed-size binary fields (big-endian integers)
- `ByteBuffer` provides clean API for reading multi-byte integers
- Avoids manual bit-shifting and byte arithmetic
- Well-tested JVM implementation

**Implementation Pattern**:

```clojure
(defn parse-connect-response
  "Parse UDP tracker connect response (16 bytes)"
  [^bytes data]
  (let [buf (java.nio.ByteBuffer/wrap data)]
    {:action (.getInt buf)           ; Offset 0: 32-bit int
     :transaction-id (.getInt buf)   ; Offset 4: 32-bit int
     :connection-id (.getLong buf)})) ; Offset 8: 64-bit long
```

**Compact Peer Parsing**:

```clojure
(defn parse-compact-peers-ipv4
  "Parse compact peer list (6 bytes per peer)"
  [^bytes peers-data]
  (let [buf (java.nio.ByteBuffer/wrap peers-data)
        peer-count (/ (alength peers-data) 6)]
    (for [_ (range peer-count)]
      (let [ip-bytes (byte-array 4)
            _ (.get buf ip-bytes)
            ip (java.net.InetAddress/getByAddress ip-bytes)
            port (unsigned-short (.getShort buf))]
        {:ip (.getHostAddress ip)
         :port port}))))
```

**Alternatives Considered**:
- Manual byte shifting: Rejected (complex, error-prone)
- Third-party binary parsing library: Rejected (overkill for this use case)

---

### 5. Pure Function Design for Timing

**Decision**: Accept current time as explicit parameter for all time-dependent functions

**Rationale**:
- Aligns with constitutional requirement for injectable time port
- Enables deterministic testing (pass fixed timestamps)
- No hidden dependency on system clock

**Implementation Pattern**:

```clojure
(defn calculate-next-announce
  "Calculate next announce time from current time and interval.

  Parameters:
    current-time - Current timestamp (milliseconds since epoch)
    interval-seconds - Tracker-specified interval in seconds

  Returns:
    Next announce timestamp (milliseconds since epoch)"
  [current-time interval-seconds]
  (+ current-time (* interval-seconds 1000)))
```

**Exponential Backoff for Failures**:

```clojure
(defn calculate-retry-delay
  "Calculate retry delay with exponential backoff.

  Parameters:
    attempt-number - Number of failed attempts (0-indexed)
    base-delay-ms - Base delay in milliseconds (default 1000)
    max-delay-ms - Maximum delay cap (default 60000)

  Returns:
    Delay in milliseconds before next retry"
  [attempt-number base-delay-ms max-delay-ms]
  (min max-delay-ms
       (* base-delay-ms (Math/pow 2 attempt-number))))
```

**Testing**:
```clojure
(deftest test-calculate-next-announce
  (is (= 1000 (calculate-next-announce 0 1)))   ; 0 + 1 second = 1000ms
  (is (= 31000 (calculate-next-announce 1000 30)))) ; 1s + 30s = 31s
```

---

### 6. IPv6 Support

**Decision**: Support IPv6 peers in compact format (18 bytes per peer)

**Rationale**:
- IPv6 adoption is increasing in BitTorrent networks
- Compact IPv6 format is well-defined in BEP 3
- Minimal additional complexity (same parsing pattern as IPv4)

**Implementation**:

```clojure
(defn parse-compact-peers-ipv6
  "Parse compact IPv6 peer list (18 bytes per peer)"
  [^bytes peers-data]
  (let [buf (java.nio.ByteBuffer/wrap peers-data)
        peer-count (/ (alength peers-data) 18)]
    (for [_ (range peer-count)]
      (let [ip-bytes (byte-array 16)
            _ (.get buf ip-bytes)
            ip (java.net.InetAddress/getByAddress ip-bytes)
            port (unsigned-short (.getShort buf))]
        {:ip (.getHostAddress ip)
         :port port}))))
```

**Tracker Response Detection**:
- Check if `peers` response is divisible by 6 (IPv4) or 18 (IPv6)
- May include `peers6` field for IPv6 peers separately

---

## Summary

### Key Technologies Selected

| Component | Technology | Rationale |
|-----------|------------|-----------|
| HTTP Protocol | BEP 3 spec | Standard BitTorrent tracker protocol |
| UDP Protocol | BEP 15 spec | Low-latency alternative to HTTP |
| URL Encoding | Custom RFC 3986 impl | Correct percent-encoding for binary data |
| Binary Parsing | Java ByteBuffer | Clean API for multi-byte integers |
| Time Handling | Explicit parameters | Enables pure functions and deterministic tests |
| IPv6 Support | Compact format (18 bytes) | Future-proof for IPv6 adoption |

### Implementation Priorities

1. **Phase 1 (P1-P2)**: HTTP tracker protocol
   - HTTP request URL building with proper encoding
   - HTTP response parsing (bencode + compact peers)
   - Priority: HTTP is more common than UDP

2. **Phase 2 (P3-P4)**: UDP tracker protocol
   - UDP message building (connect, announce)
   - UDP message parsing (responses)
   - Connection ID lifecycle handling

3. **Phase 3 (P5-P6)**: Error handling and scheduling
   - Tracker error response parsing
   - Re-announce interval calculations
   - Exponential backoff for failures

### Testing Strategy

- **Unit tests**: Pure function tests with hardcoded byte arrays
- **Property-based tests**: Round-trip encoding/decoding (URL encoding, binary parsing)
- **Integration tests**: Fake network port returns canned tracker responses
- **No actual network I/O**: All tests use test doubles

### Open Questions

None. All research complete and decisions documented.

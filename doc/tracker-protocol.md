# Tracker protocol

Peers come from trackers. You announce your torrent to a tracker, and it answers with a list of peers plus a re-announce interval. This feature implements both tracker transports — HTTP ([BEP 3](https://www.bittorrent.org/beps/bep_0003.html)) and UDP ([BEP 15](https://www.bittorrent.org/beps/bep_0015.html)) — as pure functions in `dev.cljtoc.protocol.tracker`. Network I/O stays behind `INetworkPort`; the clock stays behind `ITimePort`.

## HTTP announce (BEP 3)

You build an announce URL, GET it, and parse the bencoded response.

Required query parameters: `info_hash`, `peer_id`, `port`, `uploaded`, `downloaded`, `left`. Optional: `event` (`started`, `completed`, `stopped`), `compact`, `no_peer_id`, `numwant`.

A success response carries some of `interval`, `min interval`, `tracker id`, `complete`, `incomplete`, and `peers`. A failure response carries `failure reason` — parse it into an error map, not an exception.

### Peer formats

* **Compact IPv4** — 6 bytes per peer (4-byte IP + 2-byte big-endian port). Preferred; saves roughly 80% bandwidth.
* **Compact IPv6** — 18 bytes per peer (16-byte IP + 2-byte port), sometimes under a separate `peers6` field.
* **Dictionary** — legacy list of maps with `peer id`, `ip`, and `port`.

## UDP tracker (BEP 15)

UDP skips the TCP handshake, so it is faster but unreliable — timeouts and retries belong to the layer above.

1. Send a 16-byte **connect request** (magic `0x41727101980`, action `0`, random transaction id) and read back the **connection id**.
2. Send **announce** / **scrape** requests tagged with that connection id.
3. Reconnect when the id expires (60 seconds of inactivity).

All integers are big-endian. Every response echoes your transaction id — verify the match before you trust the payload. Event codes: `0` none, `1` completed, `2` started, `3` stopped.

## Timing

You compute the next announce from the tracker's answer: prefer `min interval` when present, else `interval`, else a 1800-second default. Failed announces back off exponentially. Pass the current time in as an argument so your tests stay deterministic.

## Entities

* **TrackerRequest** — what to send: request type, protocol, info-hash, peer-id, port, statistics, options.
* **TrackerResponse** — what you got: peer list, seeder/leecher counts, interval, tracker or connection id, warnings.
* **Peer** — one address: IP, port, optional peer-id.
* **AnnounceSchedule** — when to ask again: next announce time, interval, retry state.
* **TrackerError** — what failed: error type (network, protocol, tracker), message, tracker URL, timestamp.

Every entity has a `clojure.spec` definition with protocol-aware generators (exact 20-byte hashes, valid IPs, ports 1024–65535). Public functions validate inputs at the boundary and translate spec failures into `{:error ...}` maps with explain-data attached. Instrumentation is opt-in for dev/test and off in production.

## Errors

Functions return `{:ok value}` or `{:error ...}` and never throw for expected failures. Malformed responses, HTTP 4xx/5xx, UDP error actions, and timeouts each produce a distinct error that preserves the tracker URL and the failure context, so the caller can tell a dead tracker from a broken parser.

## Key decisions

* **Custom RFC 3986 percent-encoding** for `info_hash` and `peer_id`. Java's `URLEncoder` writes `+` for spaces; trackers expect `%20`.
* **Java `ByteBuffer`** for UDP parsing. Fixed big-endian fields map cleanly onto it; hand-rolled bit-shifting proved error-prone.
* **Explicit time parameters** instead of reading the clock, per the architecture's injectable-time rule.
* **IPv6 compact peers** supported from the start (18 bytes each) using the same pattern as IPv4.

## Further reading

* [HTTP contract](tracker-http.md) — request builder, response parser, peer-list parsers.
* [UDP contract](tracker-udp.md) — connect, announce, scrape, and error messages.
* [fdef usage guide](tracker-fdef.md) — spec definitions, runtime validation, generative testing.

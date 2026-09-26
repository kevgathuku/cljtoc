# HTTP and UDP trackers with binary-safe encoding

Peer discovery speaks both HTTP announces (BEP 3) and UDP announces (BEP 15), preferring compact peer format with dictionary fallback, falling back across `announce-list` trackers on failure, and backing off exponentially (1s base, 1h cap, 1800s default interval). Binary URL fields use a hand-written RFC 3986 percent-encoder and UDP integers go through `java.nio.ByteBuffer` for big-endian binary.

## Considered Options

- **Java URLEncoder**: rejected — encodes space as `+` instead of `%20`, corrupting binary info-hashes.
- **Manual byte-shifting for UDP**: rejected — complex and error-prone next to `ByteBuffer`.
- **Third-party binary/codec libraries**: rejected — overkill for two small, stable needs.

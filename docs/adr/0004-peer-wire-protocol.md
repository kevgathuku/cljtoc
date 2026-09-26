# Peer wire as records plus a pure state machine

Handshake and all nine BEP-3 messages are Clojure records built/parsed as complete byte arrays, and per-peer posture (choking, interest, availability) evolves through the pure `(state, event) → state` function `apply-message`. The protocol layer never sees TCP framing or stream reassembly, caps blocks at 16 KiB, ignores BEP 10 / fast-extension messages (preserving reserved bytes), and stores bitfields as `java.util.BitSet` behind an immutable abstraction.

## Considered Options

- **Binary libraries (byte-streams, gloss)**: rejected — extra dependencies for byte-array work Clojure interop already covers; `ByteBuffer` considered but the functional approach reads cleaner here.
- **BEP 10 and BEP 6 support**: explicitly out of scope — core transfer first.

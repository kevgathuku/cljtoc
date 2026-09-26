# Single persistence seam with byte-faithful round-trip

`dev.cljtoc.ports.disk` owns download persistence: `encode-state` (records to maps, byte arrays to tagged `{:cljtoc/bytes hex}` maps), `decode-state`, and `id-from-path` (human-readable string ID from the torrent filename, created once in `start-download`). `DiskPortImpl` and `cli.state` are thin adapters over the same `./torrent-state/<id>.edn` layout, and every load path decodes — so a resumed download carries real bytes into handshake and piece verification. Supersedes the uuid `Download.id` in the 006 spec docs (issue #7).

## Considered Options

- **Two seams (disk port + CLI state with separate encodings)**: rejected — divergent IDs and serializations; raw `pr-str` of byte arrays emits `#object` tags no reader accepts.
- **Bare hex strings without decode**: rejected — resumed hashes reach byte-oriented code as strings and handshake/verification fail.
- **Schema-aware decode of known fields**: rejected — a field list rots; the tag is self-describing.

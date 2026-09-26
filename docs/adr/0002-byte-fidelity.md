# Byte fidelity: raw binary strings, exact-bytes info hash

Bencode strings are raw binary byte arrays, never UTF-8 text — filenames and hashes are not necessarily valid UTF-8. Consequently the info hash is computed over the info dictionary's exact original bytes sliced from the `.torrent` file, never by decode-then-re-encode.

## Considered Options

- **Decode → re-encode for hashing**: rejected — re-encoding may reorder dictionary keys and silently produce a wrong identity hash.

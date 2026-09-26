# Torrent Downloading

The BitTorrent download domain: describing content, finding peers, exchanging blocks, and assembling verified files.

## Torrent description

**Torrent metainfo**:
The parsed description of a torrent's content and origin: tracker URLs, authorship fields, and the info dictionary.
_Avoid_: torrent metadata

**Info dictionary**:
The content-describing part of the metainfo: piece size, piece hashes, and single- or multi-file layout.

**Info hash**:
A torrent's canonical identity: the SHA-1 of the info dictionary's exact original bytes, never a re-encoding.

**Piece hash**:
The expected SHA-1 of one complete piece's bytes; the ground truth for integrity.
_Avoid_: info hash (torrent identity, not piece identity)

**Contained file**:
One file inside a multi-file torrent, named by path components with a byte length. Not the `.torrent` file itself.

## Swarm and tracking

**Tracker**:
The coordination server that records which peers participate in a torrent's swarm.

**Announce**:
A peer's periodic declaration to the tracker of identity, torrent, port, and transfer totals, plus its lifecycle event.
_Avoid_: request (means a block request on the wire)

**Peer**:
A participant in a torrent's swarm. Its location is a peer address; a live exchange with it is a peer connection.
_Avoid_: peer endpoint

**Peer connection**:
A live link to a peer carrying the handshake, availability, choking and interest posture, and block traffic.

**Peer address**:
A peer's network location: a host plus a port, written canonically as `host:port` (`[host]:port` for IPv6).
_Avoid_: peer string, endpoint, peer ID

**Swarm exhaustion**:
No live peer connections, no dials still in flight, and pieces still incomplete — the only condition under which the coordinator fails a download for lack of peers. Connected-but-choking peers and tracker re-announce are deliberately outside this definition.

## Peer exchange

**Handshake**:
The mandatory opening exchange of a peer connection, establishing protocol, torrent identity, and peer identity.

**Block request**:
A peer-wire message asking for one block: piece index, offset, and length.
_Avoid_: request (unqualified)

**Block**:
One network-sized byte range inside a piece. A peer-wire `Piece` message carries exactly one block's data.

**Piece availability**:
The set of pieces a peer holds, advertised as a bitfield or single-piece announcement.
_Avoid_: bitfield (the encoding, not the concept)

## Pieces and download

**Piece**:
A fixed-size chunk of torrent content, except for a possibly shorter final piece; the unit of verification.
_Avoid_: the peer-wire `Piece` message (which carries one block)

**Piece state**:
The torrent-wide snapshot of every piece as needed, in-flight, or verified.

**Endgame mode**:
The closing phase of a download, when few pieces remain and duplicate requesting beats exclusivity.

**Download**:
One single-torrent download job: torrent description, piece state, peer connections, lifecycle state, and statistics together.

**Download progress**:
A point-in-time snapshot of a download's completion, transfer totals, rate, and connected-peer count.

# Download orchestration

Orchestration turns the pure pieces — parsing, discovery, wire messages, piece logic — into a running download. You start a torrent, watch progress, pause and resume across restarts, and stop cleanly. Implementation lives in `dev.cljtoc.orchestration.download` and `dev.cljtoc.orchestration.coordinator`, with peer workers under `dev.cljtoc.coordination`.

## Lifecycle

A download moves through explicit states: `:idle → :starting → :downloading`, ending in `:completed`, `:paused`, or `:failed`. The states exist so pause/resume and error reporting have crisp boundaries — progress reads come from `:downloading`, and `:failed` carries the reason instead of crashing the supervisor.

* **Start** — parse the torrent, announce to trackers, open peer connections, seed piece state.
* **Progress** — query any time for percentage, verified-piece count, aggregate rate, and peer count.
* **Pause** — close peer connections and persist piece state; resume reconnects and continues where you left off.
* **Stop** — shut down workers and release resources.

## How the pieces fit

| Step | You call | From |
|---|---|---|
| Parse the file | `bencode/parse-torrent` | [bencode parser](bencode-parser.md) |
| Discover peers | `tracker/announce` | [tracker protocol](tracker-protocol.md) |
| Talk to peers | `peer/connect`, `peer/send-message` | [peer wire protocol](peer-wire-protocol.md) |
| Track and pick pieces | `pieces/initial-piece-state`, `pieces/select-piece`, `pieces/piece-blocks` | [piece management](piece-management.md) |
| Verify bytes | `pieces/verify-piece` | [piece management](piece-management.md) |

Corrupt pieces requeue automatically onto a different peer. Dead peers trigger reconnects to alternatives. When nothing can proceed — no peers, poisoned data, full disk — the download reports an error instead of hanging.

## Concurrency and supervision

* **core.async channels** carry peer events with backpressure from buffer sizes — no thread-per-peer overhead, no callback tangle.
* **One supervisor per peer worker** (`:one-for-one` restart). A worker that hits a corrupt peer or a dropped socket crashes alone; its siblings keep downloading.
* **No retries inside workers** — recovery belongs to supervisors, per the crash-only rule.

## Persistence

Pause writes piece state to disk through `IDiskPort` (EDN, human-readable); resume reads it back. Pieces stream to their final file layout as they verify — including pieces that span file boundaries in multi-file torrents — so memory stays flat regardless of torrent size and a SIGKILL between writes loses nothing verified.

Filesystem resolution follows the same once-per-download shape. `run-download` calls `prepare-output-layout` once after init created every declared file, freezing the alias-free layout (canonical target and filesystem identity per declared path) into an explicit prepared value threaded through the coordinator env — no hidden port state. Each piece write then goes through `write-prepared-piece`, which re-resolves the whole layout live (containment, current canonical target and identity per declared path) and runs the same whole-layout alias check as the full write path: a post-prepare alias involving any path — touched or untouched — is refused before any byte lands. A touched file redirected after prepare is refused on the touched-side stability check, also before any open. A refused prepare fails `run-download` with `:disk-error` before any peer is dialed, like a refused init. Only callers without a prepared value fall back to `write-output-piece`, which resolves the whole layout per piece: the resume-time materialization when its own prepare is refused, and event-loop writes carrying a nil `:prepared-layout`. The per-piece win over the full path is now the lack of a redundant span derivation and the cached `:sizes` map, not a smaller filesystem walk.

## Key decisions

* **Three port protocols** (`INetworkPort`, `IDiskPort`, `ITimePort`) instead of direct I/O — the whole download runs against test doubles.
* **Explicit state enumeration** over a boolean flag — pause and failure need names, not inferences.
* **Write pieces as they verify** instead of buffering — bounded memory for arbitrarily large torrents.

## Further reading

* [Orchestration contracts](orchestration-contracts.md) — lifecycle API, coordination handlers, port interfaces.

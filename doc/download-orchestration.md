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

The supervision layer lives in `dev.cljtoc.supervision.*` and sits above the coordination layer in the four-layer architecture. See [ADR-0011](../adr/0011-supervision-layer.md) for the design rationale.

* **core.async channels** carry peer events with backpressure from buffer sizes — no thread-per-peer overhead, no callback tangle.
* **Two supervisor namespaces.** A peer supervisor (one-for-one) owns the peer workers and their per-connection restart budget. A download supervisor (rest-for-one) owns the coordinator plus the re-announcer (`#22`) and watchdog (`#23`) children. Both live inside one CLI invocation; cross-invocation recovery still goes through `torrent.resume` and the persistence seam.
* **No retries inside workers** — recovery belongs to supervisors, per the crash-only rule. A worker that hits a corrupt peer or a dropped socket exits cleanly via an `INetworkPort` envelope `:error`; the peer supervisor observes the done-channel close and decides restart-or-drop against its per-connection budget.
* **Per-connection restart budget at the peer layer.** Each handshake attempt is its own budget window; a flapping peer that drops `N` times in `T` seconds is dropped from the address set, but a fresh connection attempt at the same address starts with a fresh budget. One supervisor-wide prune timer (every `T/4` seconds) sweeps old entries from the in-memory map.
* **Per-supervisor intensity budget at the download layer.** The whole coordinator subtree is bounded by a sliding-window intensity; the download supervisor is charged on coordinator crashes, swarm-exhaustion transitions, and re-announce failures — *not* on every peer restart (those stay at the peer layer).
* **Crash detection is the port-envelope boundary.** Clean exits are `INetworkPort` envelope `:error` returns; crashes are spec failures, NPEs, or malformed-message exceptions. Both surface as done-channel closes; the supervisor logs them differently but budgets them identically.
* **Clean shutdown on SIGINT.** A shutdown hook signals the peer supervisor to stop: peer connections close, in-progress state persists through `IDiskPort`, then the JVM exits cleanly. Without this, JVM-default SIGINT kills workers mid-piece-write and loses everything since the last save. Periodic persistence remains out of scope — file a follow-up issue for save-during-run.

## Persistence

### Piece writes

After a piece verifies, you get it in its final files before anything counts it done — the loop edge writes each verified piece through `IDiskPort` as it verifies, and resume replays cached verified bytes the same way. A piece that spans files lands across each one, touched files are truncated to their declared length, and a failed write fails the download while keeping earlier progress. For the resolution and write mechanics, see `dev.cljtoc.ports.disk-impl/write-layout!`.

Pause writes piece state to disk through `IDiskPort` (EDN, human-readable); resume reads it back. Pieces stream to their final file layout as they verify — including pieces that span file boundaries in multi-file torrents — so memory stays flat regardless of torrent size and a SIGKILL between writes loses nothing verified.

Filesystem resolution follows the same once-per-download shape. `run-download` calls `prepare-output-layout` once after init created every declared file, freezing the alias-free layout (canonical target and filesystem identity per declared path, plus a parent-directory mtime snapshot) into an explicit prepared value threaded through the coordinator env — no hidden port state. Each piece write then goes through `write-prepared-piece`, which consults the mtime gate first: when no parent dir changed since prepare, only the touched files are re-resolved and checked for touched-side stability (one stat per distinct parent plus O(touched) work — flat in file count for the usual layouts where many files share few dirs); when a parent changed, the whole layout is re-resolved live and the same whole-layout alias check as the full write path runs, so a post-prepare alias involving any path — touched or untouched — is refused before any byte lands. A touched file redirected after prepare is refused on the touched-side stability check, also before any open. A refused prepare fails `run-download` with `:disk-error` before any peer is dialed, like a refused init. Only callers without a prepared value fall back to `write-output-piece`, which resolves the whole layout per piece: the resume-time materialization when its own prepare is refused, and event-loop writes carrying a nil `:prepared-layout`. Measured probe (1024-byte files, initialized layout, unmutated tree): per-piece prepared-write cost flat at ~0.6ms at 50/200/1000 files.

## Key decisions

* **Four port protocols** (`INetworkPort`, `IDiskPort`, `ITimePort`, `IRandomnessPort`) instead of direct I/O — the whole download runs against test doubles. `IRandomnessPort` is a primitive seam over peer-id generation; jitter math lives in a separate pure `dev.cljtoc.domain.backoff` module so the supervisor slices (issue #28) and re-announce (#22) and watchdog (#23) all share one backoff implementation. Per ADR-0011.
* **Explicit state enumeration** over a boolean flag — pause and failure need names, not inferences.
* **Write pieces as they verify** instead of buffering — bounded memory for arbitrarily large torrents.

## Further reading

* [Orchestration contracts](orchestration-contracts.md) — lifecycle API, coordination handlers, port interfaces.

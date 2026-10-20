# Daemon session owns N torrents across invocations

Supersedes the "Persistent supervisor across CLI invocations (daemon mode): rejected" option in [ADR-0011](0011-supervision-layer.md). That rejection kept the supervisor inside one blocking CLI call; the goal is a Transmission-style daemon: one long-lived session process owns every torrent, and CLI invocations are thin clients against it.

## Transmission mapping

- `tr_sessionInit(config_dir, settings)` creates one session bound to a config dir (client settings → `settings.json` → defaults), starting bandwidth, DHT/PEX/UTP, port-forwarding, blocklist, and the announcer on the session thread (`libtransmission/session.cc`).
- `tr_sessionLoadTorrents` reloads every `torrents/*.torrent` plus `resume/*.resume` at startup. Adding a torrent (`torrent-add`) works at any time against the running session, never via restart argv.
- `daemon/daemon.cc` is a thin wrapper: config dir → `sessionInit` → `loadTorrents` → RPC server plus watchdir → daemonize/foreground loop with pidfile and signal handling. `transmission-remote`, the GUI clients, and the web UI all talk RPC to the already-running session.
- Torrents are independent entries in `tr_torrents` with per-torrent state machines under global queue, scheduler, and bandwidth. One torrent failing never restarts its siblings.

## Decision

- The **session is a process, not a value**. The current `dev.cljtoc.orchestration.session` record (one map per CLI invocation, born from argv, dead at completion) is the stepping stone from `torrent-client-clj-0c9.6`, not the end state.
- The daemon owns the config dir, loads all torrents plus resume state at start, accepts add/remove while running, persists per-torrent state periodically and on SIGTERM, and shuts down via a session-close equivalent. Slices: `torrent-client-clj-0c9.13` (per-subtree fault isolation), `torrent-client-clj-0c9.14` (RPC: start returns, status/pause/resume query the live supervisor), `torrent-client-clj-0c9.18` (periodic persistence plus SIGTERM shutdown), `torrent-client-clj-0c9.12` (daemon shape).
- Supervisor hierarchy gains a tier: session supervisor → one download supervisor subtree per torrent (coordinator, peer supervisor, re-announcer, watchdog). Rest-for-one stays scoped per download subtree so one flaky torrent never restarts its siblings.
- One peer port, one peer id, one bandwidth/queue instance per daemon, shared across torrents — two concurrent CLI runs must never double-bind them.

## Considered Options

- **Keep ADR-0011 Option A (blocking CLI) as the end state**: rejected — every CLI run would re-announce, re-bind the peer port, and re-own DHT state; torrents added yesterday are invisible to today's invocation.
- **Perfect the single-shot CLI first (queues, limits on the value-session), convert to daemon later**: rejected as the default — queue (`0c9.7`) and speed-limit (`0c9.5`) logic built on a throwaway lifetime needs rework at daemon time. If downloading-first scheduling forces it, record the rework debt explicitly.
- **Persist supervisor counters to get daemon recovery**: rejected already in ADR-0011 and still rejected — recovery goes through the Download-record persistence seam, not supervisor internals.

## Consequences

- `torrent-client-clj-0c9.13` is fault isolation only ("one subtree restart leaves siblings untouched"); it must not entrench the blocking `run-downloads` shape that `0c9.14` replaces.
- `doc/architecture.md` roadmap and `doc/download-orchestration.md` sessions section name the daemon as the target and the current session record as the stepping stone.
- ADR-0011 stays as history; this ADR records the reversal of its daemon rejection and why.

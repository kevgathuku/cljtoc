# Orchestration behind port protocols over core.async

All effects cross three injectable ports (`INetworkPort`, `IDiskPort`, `ITimePort`); peer concurrency runs on core.async channels with explicit ownership, and every runtime fact lives in the `Download` record — no global atoms. Verified pieces stream straight to the file layout via piece-to-file offsets instead of buffering in memory, and the lifecycle is the explicit states `:idle → :starting → :downloading → :completed | :paused | :failed`.

## Considered Options

- **Thread-per-peer**: rejected — overhead at 50+ connections.
- **Java NIO callbacks**: rejected — harder to test and unidiomatic here.
- **Manifold / thread pools**: rejected — extra dependency / less composable than channels.
- **Direct I/O, global effect atoms, ambient environment**: rejected — they violate explicit-effects and no-hidden-state principles.
- **Buffer-all-then-write**: rejected — memory-intensive for large torrents.

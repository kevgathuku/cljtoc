# Prove It Works

**Principle:** Every task output must be verified by checking the real thing directly — not by inferring from proxies, self-reports, or "it compiles."

## Why

Unverified work has unknown correctness. Indirect verification (file mtimes, output freshness, agent self-reports, cached screenshots) feels cheaper than direct observation, but acting on a wrong inference costs far more than checking the source.

## Pattern

After completing any task, ask: **"How do I prove this actually works?"**

### Check the real thing, not a proxy
- **Check process liveness directly** (PID, process table), not indirectly (test-double-only runs, file mtime, agent self-reports).
- **Read the actual value** — real state bytes on disk, live RPC answers — not a cached or derived representation.
- **When verification fails, suspect the observation method** before suspecting the system.

### Code / Features
1. Build it (`lein test` green is necessary but not sufficient)
2. Run it (`lein run`) and exercise the actual feature path
3. Check the full chain: does data flow from input to output?
4. For integrations (peer sockets, tracker announces, RPC), test the full communication path end-to-end over real sockets or loopback

### Test doubles vs real I/O
1. Prove domain and protocol behavior through injected port doubles — no real I/O needed there
2. Prove process and socket boundaries against the real thing: daemon PID in the process table, RPC over a real socket, one client seeding to a loopback downloader, `kill -TERM` then resume from real state bytes
3. Test error paths: bad input, missing files, timeouts

### General
- If you can run it (`lein run`, `bb` script), run it
- Prefer automated verification over manual inspection

### Delegation: trust artifacts, not self-reports

When verifying delegated work, inspect the actual output artifact (`git diff --stat`, file contents, runtime behavior) — never the delegate's summary of what they claim to have done. Agents report what they intended, not always what happened. Scope violations and silent failures are invisible in self-reports but obvious in artifacts.

## Relationship to Other Principles

[[principles/fix-root-causes]] extends this to debugging — check the real cause, not the proxied symptom.

See also [[principles/foundational-thinking]]

# LFE spike

An LFE port of piece management (`dev.cljtoc.domain.pieces`) and the
session-supervisor stop path (`dev.cljtoc.supervision.session-supervisor`).
It exists so the evidence in
[doc/lfe-migration-spike.md](../../doc/lfe-migration-spike.md) stays runnable.

The recommendation from that report is to stay in Elixir and not adopt LFE.
This directory is a spike, not a migration plan. Nothing here is built by
`lein` or shipped in the jar.

## Run the tests

```bash
cd spikes/lfe
rebar3 as test lfe ltest
```

`ltest` exits 0 even when tests fail, so read its summary. Delete
`test/*.beam` or run a clean build after restoring an older source file,
because the test runner caches compiled tests by mtime.

## Run the properties

```bash
cd spikes/lfe
erl -noshell -pa _build/test/lib/*/ebin \
  -eval 'io:format("~p~n",[proper:module(prop_torrent_pieces,[{numtests,300}])]), halt().'
```

## Run the mutation table

```bash
cd spikes/lfe
python3 run_mutants.py
```

## Interop path

Two tests call the Elixir `Torrent` and `Utils` modules. Set `ELIXIR_EBIN`
to the Elixir project's BEAM directory if it is not at the default path:

```bash
ELIXIR_EBIN=/path/to/torrent_client/_build/dev/lib/torrent_client/ebin \
  rebar3 as test lfe ltest
```

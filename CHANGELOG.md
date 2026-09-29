# Changelog

All notable user-visible changes to this project are documented here, newest first.
This log follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Behavior changes under `src/` add an entry under `[Unreleased]`; internal refactors and docs-only commits are exempt.

## [Unreleased]

### Fixed
- Prepared per-piece writes (`write-prepared-piece`) now re-validate the whole layout live on every piece, refusing a post-prepare alias that points an untouched declared path at a touched file (e.g. a symlink or hard link created after `prepare-output-layout`). Previously, an untouched path newly aliased onto a touched target slipped through because the untouched snapshot was never re-scanned. Per-piece filesystem cost is now O(files) on the prepared path (matching the full write path); the per-piece win over the full path is the cached compiled layout and the cached `:sizes` map, not a smaller filesystem walk.

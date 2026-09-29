# Changelog

All notable user-visible changes to this project are documented here, newest first.
This log follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Behavior changes under `src/` add an entry under `[Unreleased]`; internal refactors and docs-only commits are exempt.

## [Unreleased]

### Fixed
- Prepared per-piece writes (`write-prepared-piece`) now re-validate live on every piece through a parent-directory mtime gate, refusing a post-prepare alias that points an untouched declared path at a touched file (e.g. a symlink or hard link created after `prepare-output-layout`). Previously, an untouched path newly aliased onto a touched target slipped through because the untouched snapshot was never re-scanned. When no parent dir changed since prepare, only the touched files are re-resolved (one stat per distinct parent plus O(touched) work — flat in file count); when a parent changed, the whole layout is re-resolved and the full alias check runs. Measured probe (1024-byte files, initialized layout, unmutated tree): per-piece prepared-write cost flat at ~0.6ms at 50/200/1000 files.

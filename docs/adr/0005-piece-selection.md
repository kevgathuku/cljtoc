# Set-based piece state with linear rarest-first selection

`PieceState` is a record of persistent sets (needed, in-flight, verified), and `select-piece` accepts peer availability as plain index sets — never `BitSet`s — keeping the domain pure and testable. Selection is rarest-first by linear frequency count with lowest-index tie-break; endgame is a pure predicate on remaining pieces against a caller-supplied threshold, with duplicate in-flight requests allowed.

## Considered Options

- **`BitSet` inside the domain or at the selection seam**: rejected — clone-before-mutate interop in pure code, harder tests, protocol-layer coupling.
- **Maintained frequency table**: rejected — premature optimisation over a linear scan.
- **Random tie-break**: rejected — nondeterministic and harder to test.
- **Hard-coded endgame threshold**: rejected — policy belongs to the caller, mechanism here.

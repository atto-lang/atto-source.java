# Oreslang concurrency formal checking and analyzer (Rust prototype)

All executable verification and lint implementation is in the Rust crate
`tools/oreslang-verify/`. No Python dependency or Python source is needed.

## Run

```bash
cargo test --manifest-path tools/oreslang-verify/Cargo.toml --all-targets
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- model --actors 2 --messages 2 --workers 2 --capacity 1
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- lint --format json examples/
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- lint --pedantic examples/
```

The Rust checker enumerates all reachable states **within explicitly finite bounds**,
and fails rather than declaring success on reaching the configured state limit.
Checks include actor lease exclusivity, mailbox bounds, message uniqueness and
bookkeeping, continuation status and termination cleanup. A counterexample
reports the transition path. This is **not yet** a proof that the Java/LLVM
implementations refine this model, nor a liveness/fairness proof.

The Rust userland linter tokenizes Oreslang excluding comments and string
literals. It currently reports: ORES101 loop-declared defer, ORES102 await in
apparently synchronous function; pedantic-only ORES103 select without a
syntactic timeout/default and ORES104 match without a syntactic wildcard.
Warnings are advisory, not a typed exhaustive analysis or security gate.
Missing catch/recover can be intentional and must be checked using effect
information before it can be called an actual defect.

## Next integration and proof obligations

1. Generate TypeSpec/JSON-schema contracts for actor lease, mailbox, select
   registration/winner/cancel, continuation wake, close, restart and hot-load
   generation events. Check **real** Java and LLVM traces against the Rust
   model with an explicit refinement mapping.
2. Model independent continuation/control/user queues and bounded select
   registration, cancellation, notification and lost-wakeup freedom.
3. Add reviewable TLA+/TLC specifications with explicit fairness assumptions
   for stronger temporal properties; Rust remains the executable tooling.
4. Feed typed AST/CFG, ownership, type, and effect data into Rust static
   analysis: exception recovery, resource closure, await suspension,
   lock/channel deadlocks, exhaustiveness and unreachable pattern arms.
5. Add SARIF, mutation tests, actual runtime trace fixtures and cross-backend
   exact-head verification; do not merge based solely on model checks.

Source Actions attempts with zero executed steps do not prove a defect. When
needed, mirror exact Git blobs and subtree into a funded test organization's
temporary branch without sharing private repository tokens.

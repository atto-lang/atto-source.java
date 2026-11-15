# Rust actor dispatch-ticket model

Source-local, dependency-free Rust port of `scripts/verify_actor_dispatch_tickets.py`.
The old script remains in place until exact-head Rust execution and parity approval; this PR does not remove or weaken any checks.

```sh
cargo fmt --manifest-path tools/actor-dispatch-model/Cargo.toml -- --check
cargo clippy --manifest-path tools/actor-dispatch-model/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path tools/actor-dispatch-model/Cargo.toml
cargo run --manifest-path tools/actor-dispatch-model/Cargo.toml
```

Model: 3 nonreusable dispatch tickets, 2 workers, cancellation, stop, and stale completion replay. Baseline oracle reachability: **88 safe states**. Mutation detector reachability: **84 states** before the forbidden stale-release witness `issue(0) -> cancel_queued(0) -> issue(1) -> enter(1,0) -> UNSAFE_stale_release(0)`. These counts were independently reconstructed from the existing model rules; Rust execution is still required.

Passing the model establishes a bounded state-machine property only. It is **not** evidence that the Java/GraalVM or other backend runtime executed or is race-free. No private source is exported to third-party repositories.

## Reusable-ticket generation fence (draft)

`tests/generation_aba.rs` extends the test model with two reusable ticket slots,
two generations per slot, two workers, cancellation, stop and delayed completion
replay. A stale completion from generation 1 must be ignored while generation 2
of the **same slot** holds an exclusive actor lease. The deliberately unsafe
same-slot-only ABA-release mutant must produce a counterexample, demonstrating
the negative control would be detected. Tests also check that stop prevents
new epochs while preserving completion of a running turn.

Run `cargo test --manifest-path tools/actor-dispatch-model/Cargo.toml` to execute
both the original nonreusable-ticket tests and the new generation-reuse tests.
The existing Rust workflow will select changes under this tool directory.
**NOT EXECUTED at creation**; record the exact-head Rust test, clippy and
format receipts before promoting the draft. This model does not modify the
Java runtime, establish JVM guest execution or certify unbounded generation
wraparound. Wraparound and production mailbox integration require separate
implementation-level evidence.

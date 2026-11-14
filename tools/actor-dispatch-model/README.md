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

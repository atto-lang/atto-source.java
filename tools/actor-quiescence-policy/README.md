> **INCOMPLETE / DO NOT MERGE:** This branch currently contains only the Cargo manifest,
> documentation, and CI workflow. The Rust validator implementation was NOT committed:
> the GitHub integration blocked the write. Consequently the crate has no `src/main.rs`
> and is not buildable. No Rust test pass or executable policy certification exists.
>
# Actor arena quiescence policy — Rust port

This is a **source-free policy validator**, not a native Oreslang runtime certification. It ports `scripts/verify_actor_arena_quiescence_matrix_v2.py` and the 27 original negative controls from `scripts/test_actor_arena_quiescence_matrix_v2.py`.

The Rust implementation uses typed JSON decoding and rejects unknown/missing/duplicate fields, incorrect scalar types, changed or reordered barrier lists, extra backend claims, zero-step CI claims, and assertions of unsupported runtime verification. This stricter behavior is intentional.

The canonical fixture is `contracts/v1/actor_arena_quiescence_matrix_v2.json`. All five backends must retain `NOT_VERIFIED` or `NOT_EXECUTED` statuses. Its `mandatory_native_evidence` object lists *requirements* and does not establish that those requirements have been met.

Rust 1.88 verification, **source-org only**:

```sh
cargo fmt --manifest-path tools/actor-quiescence-policy/Cargo.toml -- --check
cargo clippy --manifest-path tools/actor-quiescence-policy/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path tools/actor-quiescence-policy/Cargo.toml
cargo run --manifest-path tools/actor-quiescence-policy/Cargo.toml -- contracts/v1/actor_arena_quiescence_matrix_v2.json
```

Preserve the old Python reference until the exact-head Rust checks run successfully and independent differential tests confirm parity. Source-org Actions remain blocked by runner-admission issue #963; private source export is unauthorized under issue #962. Do not substitute independent source-free canary success for exact-source compiler or AOT evidence.

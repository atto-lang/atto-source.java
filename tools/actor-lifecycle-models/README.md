# Rust actor lifecycle models

Rust-first, dependency-free replacements for two Python-only bounded reference models:

- `scripts/verify_actor_carrier_exclusivity.py`
- `scripts/check_actor_teardown_state_machine.py`

The Python reference implementations are **retained** until independently reviewed exact-head Rust execution confirms equivalent behavior. The new tests do not claim a real Java/LLVM/WASM/.NET/Forge runtime execution.

## Exact reference expectations

| Model | Expected |
| --- | --- |
| Safe carrier-lease BFS | 372 reachable states, no exclusive-lease violation |
| Unsafe double carrier | 65 visited states and `claim actor=0 job=0 worker=0 -> claim actor=0 job=1 worker=1` counterexample |
| Corrected teardown wake | 17 reachable states, no stranded pending teardown |
| Old teardown wake | 17 visited states, `carrier-enter -> guest-return -> stop -> carrier-exit` stranded counterexample |

State counts and witness paths were independently reconstructed from the reference transition rules. They still require verification by actual Rust 1.88 execution.

## Run with Rust 1.88

```bash
cargo fmt --manifest-path tools/actor-lifecycle-models/Cargo.toml -- --check
cargo clippy --manifest-path tools/actor-lifecycle-models/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path tools/actor-lifecycle-models/Cargo.toml
cargo run --manifest-path tools/actor-lifecycle-models/Cargo.toml
```

CI must show `runner_id > 0` and nonempty executed steps. No checkout or exact-head private Oreslang source is authorized to move to an external organization under the current destination security policy. The source org has unresolved runner-admission incident #963.

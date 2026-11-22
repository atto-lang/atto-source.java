# Rust optional/named/rest call-plan reference

This source-free Rust crate replaces the Python reference tests formerly in tests/test_optional_call_plan_reference.py. It preserves the seven original scenarios and extends negative coverage for duplicate binding, typed rest packs, named omission, empty signatures and exact expression evaluation order.

Run from the repository root:

    cargo fmt --manifest-path tools/optional-call-plan-reference/Cargo.toml --check
    cargo clippy --manifest-path tools/optional-call-plan-reference/Cargo.toml --all-targets -- -D warnings
    cargo test --manifest-path tools/optional-call-plan-reference/Cargo.toml --offline

No external dependencies or Python execution. IMPORTANT: these are reference-model tests, not Java parser/typechecker tests, real .atto guest execution, AOT tests, or five-backend conformance evidence. Those remain separate release gates.

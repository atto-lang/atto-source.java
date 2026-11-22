# Rust Actions CI triage migration — staged, not authoritative

This draft replaces the pure evidence-classification model first and now
**stages** a separate read-only GitHub API adapter with JSON validation,
per-attempt job pagination and current PR/run rechecks.

Code paths:

- `tools/ci_run_triage_core.rs` — dependency-free classification and adversarial fixtures
- `tools/ci-run-triage/src/main.rs` — read-only GH GET client with strict
  duplicate-key JSON rejection, 8 MiB response limit, 20-second external
  command bound, bounded 100-per-page attempt-scoped jobs retrieval,
  duplicate job-ID rejection, exact SHA/PR/attempt checks, and safe output
- `tools/ci-run-triage/Cargo.toml` — serde/serde_json dependencies
- `.github/workflows/rust-ci-triage-stage.yml` — isolated stage tests
  on the source organization's ubuntu runner; **not a required parity gate**

Run after Rust is available:

```bash
rustc --edition=2021 -D warnings --test tools/ci_run_triage_core.rs   -o /tmp/ci-run-triage-core-tests
/tmp/ci-run-triage-core-tests
RUSTFLAGS='-D warnings' cargo test --manifest-path   tools/ci-run-triage/Cargo.toml --all-targets
```

**Verification status:** The Rust tests and production adapter are authored
but have NOT been compiled or executed in this work environment. The
GitHub source organization has failed runner admission, so the staged
workflow does not establish a passing Rust test verdict. The manually
mocked LLVM authorization checks in another repository are separate
and cannot substitute for Java Rust evidence.

## Authority / promotion requirements

The current Python `scripts/ci-run-triage.py` and
`tests/test_ci_run_triage.py` remain authoritative. The new Rust
adapter is not wired into `fast-ci.yml` and should never call the
private CI export publisher. Before promotion:

1. Execute exact-head Rust compilation/tests with nonzero-step runner proof.
2. Reconcile every existing Python adversarial test and compare outputs,
   including malformed timestamps, duplicate/paged jobs, changed run
   attempts, stale PR heads, and complete versus missing evidence.
3. Pin/test Cargo dependencies, run `cargo fmt --check` and
   `cargo clippy --all-targets -- -D warnings`.
4. Keep both Python and Rust tooling while migrating; remove Python only
   after equivalent tested security behavior exists. Do not weaken scanners.
5. Independently review and enforce the source main governance controls.

No private-source mirroring to another GitHub organization is authorized
by these staged changes. Refer to issue #2342 and CI smoke PR #2336.

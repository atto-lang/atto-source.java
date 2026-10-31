# Rust Actions evidence triage migration: stage 1

This source-local, dependency-free Rust classifier is an initial semantic
replacement for the pure classification layer of the Python CI triage tool.
It does not yet fetch GitHub API data or parse JSON. The original Python
runner and its adversarial tests remain in place and authoritative until
all pagination, fail-closed response parsing, current PR-head re-checks,
and run-attempt provenance tests are ported and validated.

The intended offline test command after Rust is available is:

    rustc --edition=2021 -D warnings --test tools/ci_run_triage_core.rs -o /tmp/ci-run-triage-tests
    /tmp/ci-run-triage-tests

Tests are AUTHORED, NOT EXECUTED. No rustc/cargo toolchain was present in
the current execution environment; source-org GitHub Actions jobs are
failing before a runner is admitted. Do not remove Python files, weaken
the Rust-only scanner, or swap fast-ci until parity is demonstrated.

See issue #2342 for the full Python-to-Rust migration and draft #2336
for separate source-free runner smoke hardening.

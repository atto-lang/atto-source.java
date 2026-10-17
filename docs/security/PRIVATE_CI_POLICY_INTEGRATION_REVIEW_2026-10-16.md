# Private-CI policy + source-local runner reconciliation (draft)

This is a **current-main-based integration candidate only**. Neither the files nor the branch authorize private-source export, activate a runner, or permit a merge without independent review.

## Source lines / decisions

- Start from immutable Java main commit `12cdd17af7f949a3c54e045a46a2fc21be0ea5d0`, tree `939c88979417da9c0b4bc7bcf045c82e2e477483`.
- Preserve the comprehensive fail-closed publisher, exact source snapshot approval policy, CI preflight and 147 checked-in Python adversarial tests from Java security PR #954, commit `b9ae8b0b350f73f2e643a45400abe245f6430aad`. Preserve the existing executable modes for existing scripts.
- Source the three workflow-dispatch-only, source-repository-scoped exact-runner workflows and their JUnit workflow admission tests from Java #1014, commit `abccfd2c77ff4ae30e57ab38780372170150d4d8`.
- Union existing, #954, and #1014 CODEOWNERS entries without dropping protections. No ACL or GitHub branch setting is changed by a CODEOWNERS file.
- Related #1018 numeric identity binding, #1089 protected-source checks, and #1118 exact Git tree/TOCTOU protections overlap #954's comprehensive implementation; compare adversarial coverage rather than overwriting the publisher with a shorter, less-protective version.

## Mandatory integration security invariants

1. Source repository identity and visibility; protected `main`, effective required reviewer/CODEOWNER and strict status checks must be established independently.
2. Publisher code and approval files must match their protected-main Git blobs; exact source commit/tree and approved snapshot tuple are required **before reading private bytes**.
3. Destination numeric repo ID must match independently approved policy and remain private and active on every write; policy must be revalidated at the source. Tree/blob integrity must be independently verified before ref publication.
4. The destination and source snapshot approval files intentionally have **zero active entries**. Never use a draft policy to authorize an export.
5. Source-local workflows cannot run private candidates unless dispatched from protected, approved main with exact workflow, candidate commit, tree, and disposable repo-scoped runner controls.

## Integration test requirements (not yet claimed passed)

- `bash -n scripts/check-private-ci-destination.sh scripts/test-private-ci-destination.sh scripts/run-in-private-ci-destination.sh`.
- `python3 -I -m py_compile scripts/publish-private-ci-source.py scripts/test-publish-private-ci-source.py`.
- `bash scripts/test-private-ci-destination.sh`.
- Three runs of `python3 -I scripts/test-publish-private-ci-source.py`, with at least 147 tests each; no skips/failures/expected failures.
- Compile and run the three source-runner JUnit workflow/security tests from #1014; full JDK 25 Maven and GraalVM AOT/hybrid guest execution on exact checked-out tree after independently approved source-local runner is functional.

## Release hold

`main.protected=false` currently; source org Actions jobs are failing before runner allocation; no independent PR approvals; source export destination allowlist and exact source-snapshot approval both empty; P0 prior public source exposure is not contained. Leave draft. Do not copy private source to a funded organization or merge this branch based on source-free tests.

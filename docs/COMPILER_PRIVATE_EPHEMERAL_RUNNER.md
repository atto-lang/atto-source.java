# Exact-revision JVM compiler source-local private runner handoff

**DRAFT. No private-source transfer permission is implied.** This workflow is for `ores-truffle-oreslang/oreslang-source.java` **only**. It cannot run on an alternate organization's GitHub Actions budget, and it cannot itself register a self-hosted runner or change billing/branch protections.

The source-org GitHub-hosted jobs continue to fail before executing any steps. `.github/workflows/compiler-bundle-private-ephemeral.yml` offers a protected, explicitly authorized alternative with a disposable runner attached to the original private repository. There is no `pull_request`, `pull_request_target`, `workflow_dispatch`, or cross-org checkout trigger. A failure job denies requests without **all** independent gates; the approved job is not scheduled otherwise. Skipped jobs are not evidence of tests.

## Administrative prerequisites — all mandatory

1. Independently review this workflow and every commit in its source branch, and enforce push/force-push restrictions and required reviewed workflow changes on `ci/immutable-jvm-compiler-bundle-20261018`. **Do not enable the runner while GitHub reports `protected:false`.**
2. Confirm repository is private and immutable Git repository ID `1400792274` matches the reviewed repository. Check organization billing/Actions eligibility and choose an isolated, trusted runner registered directly to this source repository or source organization. Label it `oreslang-private-ephemeral` with `self-hosted`, `linux`, `x64`. Destroy it after one approved job. Do not attach untrusted forks or shared tenant secrets/caches.
3. Have an **independent maintainer** record the reviewed exact SHA and exact **Git tree SHA** obtained from the protected source branch. Configure source repository variables `ORESLANG_COMPILER_PRIVATE_RUNNER=true`, `ORESLANG_COMPILER_APPROVED_SHA=<reviewed head SHA>`, and `ORESLANG_COMPILER_APPROVED_TREE=<reviewed tree SHA>`. These values must refer to the same Git revision and must be changed after **any** new commit; matching the mutable branch name alone is not sufficient.
4. Trigger a *new reviewed push event on that protected source branch*, never a pull request or foreign dispatch. A zero-step failure, a skipped job, a local synthetic test or a self-reported SHA-256 are not valid compilation evidence. If existing GitHub-hosted capacity resumes, the existing source-local `compiler-bundle-proof.yml` may run instead without the ephemeral runner.
5. Require actually executed JDK25/GraalVM setup, Maven `clean verify`, strict runtime dependency packaging, static and behavioral bundle verification, positive+negative `OresMain --check` and reviewed logs. Artifact upload remains limited to the original private Java repository for one day and is **not independent source-to-binary attestation**.
6. Revoke the three approval variables, revoke the runner registration and securely destroy its VM/cache after the job. Reapprove SHA/tree for any retry or further change. Only then review the artifact and use it for the separate six pinned ML guest programs.

**No shortcut:** Security incidents `oreslang-platform-conformance#22` and `#77` and the unapproved carrier trust roots remain open. Available Actions minutes in a different organization do **not** grant authorization to copy the private source branch or compiler binary there.

## Host-only test scope

`python3 -m unittest discover -s scripts/ci -p 'test_compiler_ephemeral_policy.py' -v` tests the workflow text and an independent synthetic approval matrix. It cannot evaluate GitHub's Actions expression semantics, validate actual branch protection, register/qualify a runner, compile Java, or attest compiler provenance. The actual source-organization Actions run must execute nonzero job steps to prove any runtime result.

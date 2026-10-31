# Java source-exact ephemeral runner handoff

This workflow is an **opt-in manual execution surface**, not a runner installer or authorization to mirror private source. The workflow file must be reviewed and merged into `main` before GitHub permits `workflow_dispatch`.

## Administrator prerequisites

- Resolve Actions runner admission or provision an **ephemeral, one-job**, clean Linux x64 runner attached directly to the source organization and limited to this repository. Give it the exclusive label `oreslang-java-ephemeral-v1`; do not allow other repositories or arbitrary pull-request workflows to use the runner label/group.
- Configure its runner group and network isolation, preinstall **GraalVM JDK 25**, Maven, Git and `gh`; set no extra credentials and do not register a persistent shared machine. A source PR's build logic is untrusted even if its SHA is pinned.
- Forward runner diagnostics to trusted storage. Wipe/decommission the VM after its **first and only job**. Enforce branch protection and independent workflow/CODEOWNER review before enabling the label.
- Keep the source private-CI carrier allowlist empty and **do not** enable cross-org source export.

## Manual invocation after workflow is merged into main

Open Actions -> *Java actor memory source-exact ephemeral gate* -> Run workflow on **main**. Provide source PR number and exact current **40-hex head SHA**, e.g. from Java draft PR #2303. Optionally select `run_native_image` for the real GraalVM Native Image build.

The preflight binds supplied PR+commit to the GitHub API's **open same-repository** head, including stacked PRs. Checkout uses that exact immutable commit, discards checkout credentials and refuses dirty checkouts. After JDK 25 admission, Maven runs four actual JUnit classes. Optional Native Image is a separate step and is not claimed unless executed.

If no matching runner exists, the job can queue and is **NOT_EXECUTED**, not a passing build. Job success is valid only with nonempty completed test steps and exact-source logs. No automatic PR trigger is used because executing untrusted PR code on a persistent self-hosted runner would be unsafe.

A passing job verifies only the listed Java JUnit and optionally native-image operations. It does not prove cancellation/quiescence safety outside test coverage, native hard-RT deadlines, or parity with LLVM, .NET, WASM and Forge.

## Important: dispatch evidence is not a required PR-head status

GitHub associates a manually dispatched workflow run with the selected **main** workflow ref; checking out a different immutable PR SHA inside the job does **not** automatically attach this job's status check to that PR commit. Keep required status checks on a genuinely PR-head-triggered workflow once private GitHub Actions admission is restored, or obtain independent source-SHA receipt verification and explicit security signoff. Never auto-merge the actor PR solely because this manual job passed.

The source SHA, Git root tree, exact executed test step names and job/runner identity must be matched out of band before claiming backend execution. A missing runner, skipped job, incomplete job or zero-step failure is **NOT_EXECUTED**.

## Preflight now fails closed until protected main and reviewed PR head

Before checking out any untrusted PR source, the workflow uses its read-only GitHub token to verify that the source organization's `main` branch reports `protected: true`. Merely dispatching from `main` is insufficient. The workflow also reads the most recent **submitted** review by the dedicated `ORESoftware` identity (up to 99 records) and requires `APPROVED` at exactly the requested PR-head commit, with reviewer and author identities distinct. Missing/ambiguous/oversized review histories, stale commits, changes requested after approval, dismissal, unprotected main, and mismatched source repository all deny execution before checkout.

Currently this means the new workflow **cannot execute**: source `main` is still unprotected, and the relevant PRs have no submitted independent approvals. The admin must first apply and verify repository protection, then a reviewer must approve the *current* tested code head. If the PR changes afterward, the approval must be renewed. This is an intentional security prerequisite, not a runner admission failure. Do not disable this gate to force execution.

An approval obtained through a separate manual process must be independently checked against the exact GitHub PR head and reviewer identity. GitHub's commit-associated manual `workflow_dispatch` check still does not replace branch-protection required checks on the PR head.

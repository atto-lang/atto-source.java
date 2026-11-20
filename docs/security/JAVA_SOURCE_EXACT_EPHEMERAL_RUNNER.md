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

## Administrator JIT runner registration (local, off-Actions)

After protected `main`, independent workflow review, and a repo-restricted runner group are **actually enforced**, an authorized administrator may execute `scripts/ci/prepare_source_ephemeral_jit.sh` on a trusted **operator workstation**, not inside Actions. The script requires the organization's exact selected-repository runner-group ID and an *existing empty mode-0700 directory* for output. It checks that the group permits precisely this repository and this workflow at `@refs/heads/main`, refuses public repositories, and requires the protected source `main` and reviewed workflow to exist.

Example operator commands (illustrative only, not executed):

```bash
mkdir -m 700 -p "$HOME/.oreslang-one-job-runner"
bash scripts/ci/prepare_source_ephemeral_jit.sh RUNNER_GROUP_ID \
  "$HOME/.oreslang-one-job-runner/one-time-jit-config.b64"
```

Use an independently authenticated `gh` identity with organization **Self-hosted runners (write)** permission, scoped for this one administrative operation. **Never** put the administrative token in the runner VM, GitHub job secrets or logs. The script sends a single authorized `POST /orgs/ores-truffle-oreslang/actions/runners/generate-jitconfig` request only *after* all local policy checks pass; it writes the returned encoded JIT configuration to a newly created mode-0600 file, and prints no credential. Deliver the configuration securely to a dedicated, freshly provisioned, isolated Linux x64 runner VM with a verified GitHub Actions runner installation.

Run the official runner with the one-time `--jitconfig` value only on that disposable guest; never emit the value through shell tracing, command logging or CI summaries. Enforce single-job deletion, external runner-log forwarding, VM destruction and credential/configuration-file disposal. Beware that operating-system process argument inspection may expose a JIT argument on a multi-user host, so use **single-tenant isolated machines**. The runner label must be `oreslang-java-ephemeral-v1` and the GitHub runner group must restrict both repository and workflow. No mixed/reused job environments are permitted.

Official GitHub references: https://docs.github.com/en/rest/actions/self-hosted-runners and https://docs.github.com/en/actions/reference/security/secure-use and https://docs.github.com/en/rest/actions/self-hosted-runner-groups.

**Validation limit:** The JIT script's shell syntax and 15 source-free local Bash test cases have been executed per backend (12 independent denial cases, inside-Actions denial, secure one-time creation, and no overwrite). This does **not** confirm real organization's runner-group ID, plan eligibility, runner-image integrity, review enforcement, JIT registration, source JUnit/native execution or real GitHub management writes. No runner or secret was provisioned by this PR.

## Source-free regression command

On a trusted workstation or any ordinary CI job that does not receive an administrative GitHub token, verify the JIT provisioning fail-closed logic with the checked-in, **fully mocked** CLI:

```bash
bash -n scripts/ci/prepare_source_ephemeral_jit.sh
bash -n scripts/ci/test_prepare_source_ephemeral_jit.sh
bash scripts/ci/test_prepare_source_ephemeral_jit.sh scripts/ci/prepare_source_ephemeral_jit.sh
```

The test harness replaces `gh` with a local stub. It exercises unprotected main, overbroad runner groups, extra repository visibility, failed POST, attempts to provision inside Actions, successful 0600 credential-file creation, and refusal to overwrite an existing config. **It neither creates a real runner nor uses the private-source publisher.**

### Additional admission security checks

The administrative helper does not accept a GitHub branch response containing only `protected: true` as sufficient. It reads live source-main branch protection and requires enabled independent CODEOWNER review, stale-review dismissal, administrator enforcement, and no force-push, branch deletion, or explicit PR-review bypass. It refuses to generate a JIT credential if any required control is absent. It additionally computes `git hash-object` of its own local script and checks it against the reviewed immutable blob at `/scripts/ci/prepare_source_ephemeral_jit.sh` on protected `main`, so an unreviewed or modified workstation copy cannot generate the runner credential. Treat the local helper as untrusted until this byte-exact check succeeds.

**Executed local evidence:** the committed script and checked-in mock test both matched their GitHub blob SHA exactly; `bash -n` passed and the test runner returned `SOURCEFREE_MOCK_JIT_ADMISSION=PASS`. No live runner-authorization API calls were made. This does not authorize executing PR code without independent approval.

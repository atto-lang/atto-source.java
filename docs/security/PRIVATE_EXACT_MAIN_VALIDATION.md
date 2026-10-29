# Private exact-source validation

This document defines the source-repository validation path for private Oreslang compiler/runtime CI.

## Security boundary

These workflows are **source-org exact-source validation**. They are not a private-source mirroring mechanism and they do not authorize export of private repository contents to another organization or repository.

Keep three evidence classes separate:

1. **Source-org exact-head CI** — ordinary GitHub Actions attached to an exact PR/source SHA. A workflow/job with zero executed steps is an admission or infrastructure failure, not a failed test.
2. **SOURCE-FREE security qualification** — synthetic or source-free checks may run in another organization to validate runner admission, policy logic, or carrier behavior. They do not certify private compiler/runtime source and must never be reported as exact-head source CI.
3. **Full private-source compiler/runtime CI** — private source may execute only on the explicitly approved source-scoped ephemeral runner path described below. It must not be mirrored to another organization by these workflows.

## Required admission properties

The exact-main, exact-candidate, and publisher-regression workflows are manual-only and globally read-only. The private runner jobs require all of the following before checkout:

- exact repository slug and immutable repository ID;
- repository visibility remains private;
- protected `main` (`github.ref_protected == true`);
- an explicit `ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true` repository variable;
- exact approved workflow/candidate SHA values;
- an independently supplied expected Git tree where applicable;
- a dedicated **organization runner group** `oreslang-java-private-ephemeral-v1` **and** the exact `[self-hosted, linux, x64, oreslang-private-ephemeral]` runner labels; labels alone are not an authorization boundary.

The denial jobs run on GitHub-hosted infrastructure and must not checkout source. The private jobs use a pinned `actions/checkout` commit with `persist-credentials: false`, then re-verify the checked-out commit, root tree, and clean worktree before tool setup or tests.

## Independent approval

Changes to these workflows, their tests, this document, and the corresponding CODEOWNERS rules are owned by `@ORESoftware`. A requested review is not an approval. Security-sensitive approval must be an independent submitted review on the exact PR head; commits after that review require a new approval.

The workflow variables that name an approved SHA/tree are admission inputs, not substitutes for independent review or branch/ruleset enforcement.

## Branch/ruleset requirement

The workflows intentionally require `github.ref_protected == true`. If source `main` is not protected, the private runner job must not start. Repository policy should require pull requests, independent code-owner approval, stale-review dismissal after new commits, required exact-head checks, conversation resolution, blocked force-push/deletion, and no administrative bypass for these security gates.

## Administrator runner-group admission (not yet enabled)

**FAIL CLOSED:** the private jobs now require both `group: oreslang-java-private-ephemeral-v1` and their exact labels. A missing group must leave them queued, **not** route them to the shared/default group. No runner may be registered or dispatched until independently reviewed source `main` is protected, the three workflows are merged with CODEOWNER approval, and a fresh one-use infrastructure entitlement is approved.

An `ores-truffle-oreslang` **organization owner** must create a *custom* runner group with these enforced settings (if the plan or API cannot provide every setting, abort):

1. **Group name:** `oreslang-java-private-ephemeral-v1`. **Repository access:** *selected repositories only*, exactly `ores-truffle-oreslang/oreslang-source.java` (immutable repository ID `1400792274`). Public repositories, other repos and default-group access must not be enabled.
2. **Workflow access:** *selected workflows only*, exactly these trusted, protected-default-branch paths:
   - `ores-truffle-oreslang/oreslang-source.java/.github/workflows/private-exact-main-validation.yml@main`
   - `ores-truffle-oreslang/oreslang-source.java/.github/workflows/private-exact-candidate-validation.yml@main`
   - `ores-truffle-oreslang/oreslang-source.java/.github/workflows/private-exact-publisher-validation.yml@main`

   The separate source-free canary in PR #2172 requires its **own** group and must never be permitted to use the private-source validation group. The exact workflow allowlist must be independently inspected using GitHub's full runner-group readback; UI selection alone is insufficient evidence.
3. **Runner scope:** register an **organization-group-scoped** Linux x64 runner in that group with the unique label `oreslang-private-ephemeral`, using GitHub's true one-job `--ephemeral` or JIT registration. Do not register it to the repository or to the default group. Allocate a fresh isolated VM for each job; delete its disk/VM after the job and preserve only minimal audit receipts. Do not reuse persistent workers or run untrusted `pull_request`/`pull_request_target` workflows on this group.
4. **Credentials:** never place cloud credentials or a long-lived token on the worker. Issue registration credentials only through the approved administrator channel. Keep the workflow's `contents: read`, `persist-credentials: false` and exact reviewed SHA/tree checks; run GitHub Actions with no unauthorized secrets or network egress. No cross-organization source transfer.
5. **Admission and retirement:** confirm that a nonselected workflow/repository cannot schedule a matching-label job, record the group ID, authorized workflow paths, runner ID/name, actual executed step names, attempt, exact source SHA/tree and cleanup receipt, and destroy the VM before reusing capacity. A pending, skipped or zero-step run cannot prove admission or compiler success.

**Do not register the worker while any earlier queued workflow with this label could be admitted by the runner group.** If protected main or selected-workflow restrictions cannot be enforced, leave the validation jobs unadmitted.

## Runner evidence

A successful qualification record must retain the exact source SHA, root tree, workflow/run URL, job and step list, runner name/group/labels, test counts, and final conclusion. A completed job that contains zero steps is classified as runner admission/infrastructure failure and provides no compiler/runtime test evidence.

## Destination allowlist

These workflows do not modify or relax `.github/private-ci-approved-repositories.txt`. The export policy remains fail-closed: an empty allowlist authorizes no destination. Any future source-bearing destination requires an exact independently reviewed `owner/repo numeric-repository-id` entry plus the current publisher's provenance, incident, CODEOWNERS, and protected-origin checks.

Do not use these workflows to bypass the private-source export policy or independent `@ORESoftware` approval.

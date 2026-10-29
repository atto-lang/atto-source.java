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
- the dedicated `[self-hosted, linux, x64, oreslang-private-ephemeral]` runner label set.

The denial jobs run on GitHub-hosted infrastructure and must not checkout source. The private jobs use a pinned `actions/checkout` commit with `persist-credentials: false`, then re-verify the checked-out commit, root tree, and clean worktree before tool setup or tests.

## Independent approval

Changes to these workflows, their tests, this document, and the corresponding CODEOWNERS rules are owned by `@ORESoftware`. A requested review is not an approval. Security-sensitive approval must be an independent submitted review on the exact PR head; commits after that review require a new approval.

The workflow variables that name an approved SHA/tree are admission inputs, not substitutes for independent review or branch/ruleset enforcement.

## Branch/ruleset requirement

The workflows intentionally require `github.ref_protected == true`. If source `main` is not protected, the private runner job must not start. Repository policy should require pull requests, independent code-owner approval, stale-review dismissal after new commits, required exact-head checks, conversation resolution, blocked force-push/deletion, and no administrative bypass for these security gates.

## Runner evidence

A successful qualification record must retain the exact source SHA, root tree, workflow/run URL, job and step list, runner name/group/labels, test counts, and final conclusion. A completed job that contains zero steps is classified as runner admission/infrastructure failure and provides no compiler/runtime test evidence.

## Destination allowlist

These workflows do not modify or relax `.github/private-ci-approved-repositories.txt`. The export policy remains fail-closed: an empty allowlist authorizes no destination. Any future source-bearing destination requires an exact independently reviewed `owner/repo numeric-repository-id` entry plus the current publisher's provenance, incident, CODEOWNERS, and protected-origin checks.

Do not use these workflows to bypass the private-source export policy or independent `@ORESoftware` approval.

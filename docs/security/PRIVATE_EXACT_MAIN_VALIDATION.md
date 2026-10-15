# Exact-main private self-hosted validation (no cross-org source copies)

Workflow: `.github/workflows/private-exact-main-validation.yml`.

This is a **review-required opt-in alternative** for validating private Oreslang
`main` when GitHub-hosted Actions jobs cannot obtain minutes. It runs only in
`ores-truffle-oreslang/oreslang-source.java`; no external repository or
personal access token can checkout private source. It does not change
`.github/private-ci-approved-repositories.txt`, does not approve a destination,
and is not permission to bypass issue #991 or security review of PR #992.

## Administrator setup

1. Independently review and merge the workflow. Register a **single-use,
   ephemeral, repository-scoped** self-hosted Linux x64 runner named/labelled
   `oreslang-private-ephemeral`. Do not use a shared organization runner or
   run unreviewed PR code on it. Provision JDK 25/GraalVM-compatible Linux,
   Maven and enough disk/RAM for three sequential native builds. Provision
   without printing registration tokens or retaining build artifacts/secrets.
2. Resolve the exact reviewed `main` commit and its tree from an authenticated
   trusted checkout:
   `git rev-parse HEAD` and `git rev-parse HEAD^{tree}`.
   An administrator must explicitly set all **repository variables**:
   `ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true`,
   `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA=<40-character main commit>`,
   `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE=<40-character Git tree>`.
   Do not put tokens or secrets into these variables.
3. In the original private repository's Actions tab, dispatch
   **Private exact-main Java/Native validation**, selecting `main` only.
   Unapproved SHA/ref/visibility/runner variable combinations fail closed.
   A source-mainline advance invalidates the approval until the commit/tree
   values are deliberately updated. The workflow checkout checks immutable
   commit and tree provenance *before* invoking the toolchain.
4. Confirm that focused JUnit, full JDK 25 Maven verify, ten stress replays,
   native AOT, native hybrid, and strict-isolate guest execution all ran.
   Do not claim success from a skipped job or a GitHub Actions job with
   `steps=null`. Preserve workflow/job links and the exact commit/tree.
5. Retire the ephemeral runner and clear the approval SHA/tree and enable
   variable after the validation session. Check for lingering workspaces,
   artifacts, caches and source copies; perform the host's secure teardown.

## Safety and availability constraints

- Only `workflow_dispatch` is admitted; no `pull_request`,
  `pull_request_target`, scheduled, or unreviewed feature-branch event.
- `GITHUB_TOKEN` receives `contents: read` only; checkout discards credentials.
  Third-party Actions are SHA-pinned.
- **Never dispatch an unapproved branch and never fall back** to a hosted,
  public, or cross-org source mirror when no private runner is available.
  The negative admission check does not checkout source.
- This workflow does not repair historical unapproved private-source transfers:
  issue #991 requires a separate security disposition, and any cross-org
  mirroring still requires explicit prior review and destination approval.
- If no ephemeral runner can be provisioned, leave the validation **blocked**
  and report that status; no tests passed is preferable to fabricated CI.

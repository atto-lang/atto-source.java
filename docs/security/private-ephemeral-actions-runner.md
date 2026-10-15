# Private Oreslang GitHub Actions runner: emergency CI recovery

**Purpose:** Execute the existing JVM and native-image gates for draft PRs
[#949](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/949)
and [#950](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/950)
when the source organization's GitHub-hosted runners fail before the first step.

**Do not copy private source to another repository.** The protected
`.github/private-ci-approved-repositories.txt` currently authorizes **zero**
cross-repository destinations. In particular, `zed-pkg-test/java-app` is
public and must not receive private code. Follow the disclosure/containment
work in [security issue #832](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/832)
and the runner-admission diagnostic in
[CI issue #898](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/898).

## Safe fallback configuration (repository admins only)

1. Provision a **fresh disposable Linux x86-64 VM or isolated worker**
   solely for this private repository. Never share its work directory,
   credentials, caches, or filesystem with untrusted repositories or workloads.
   Give it no cloud/admin privileges, no application secrets, and no network
   access beyond what is required for GitHub Actions, Maven dependencies,
   GraalVM/native-image setup and trusted build tools.
2. In the **private source repository**, navigate to **Settings > Actions >
   Runners > New self-hosted runner** and follow the current GitHub instructions
   to download and verify the correct official runner package. Obtain the
   short-lived registration token through the repository's administrative
   interface. Do not commit, print, archive, or place that token in Actions
   logs, shell history or this repository.
3. Register the runner **at repository scope** (not broadly accessible
   at the organization level), with the extra exact label
   `oreslang-private-ephemeral` and GitHub's `--ephemeral` registration
   option. Preserve the default `self-hosted`, `linux`, `x64` labels.
   Run the agent as a dedicated non-root account, in a sandbox with a fresh
   working directory. A runner using `--ephemeral` handles **only one job**;
   arrange a new VM/runner for each further Maven or AOT/hybrid job and destroy
   the prior VM after completion. The label alone does **not** prove ephemeral
   isolation; administrators must enforce the VM lifecycle.
4. In **Settings > Secrets and variables > Actions > Variables**, create
   repository variable `ORESLANG_PRIVATE_EPHEMERAL_RUNNER` with exact value
   `true`. Set this only **after** the ephemeral runner is online. Until this
   is set, the dedicated branch workflows select `ubuntu-24.04` as before.
   When enabled, their `runs-on` expression selects
   `[self-hosted, linux, x64, oreslang-private-ephemeral]`.
5. Push a new reviewed commit to the trusted integration branch, or
   re-run the matching exact-head workflow when runner capacity is available.
   Existing dedicated workflows are branch-scoped and refuse job admission
   unless repository identity, private visibility and branch ref match.
   After checkout, they assert `git rev-parse HEAD == GITHUB_SHA`.
   Do not grant the runner to arbitrary `pull_request`,
   `pull_request_target`, fork, or unreviewed workflow inputs.
6. Verify that **steps actually executed**. A run marked `failure` with no
   step records is runner infrastructure failure, **not** a compiler or
   JUnit failure. For PR #949 require focused Future/channel/host-listener
   tests and native AOT/hybrid output `first:7:9`. For #950 additionally
   require typed Future/select, ownership and readonly regressions, full
   Maven `verify`, and AOT/hybrid output `future:4`.
   Record Git head/tree SHA, JDK/GraalVM version, test counts, stdout and
   final conclusions on each PR. Test evidence belongs to the **exact
   source head**, not an older or partial mirror.
7. When verification is finished, reset the repository variable to
   `false` (or delete it), stop/dispose of all ephemeral runner VMs, revoke
   lingering registration if necessary, and confirm that no private source
   remains on reusable worker disks, caches, or public artifacts.

### Limitations and non-goals

- The connected GitHub integration can edit source workflows and re-run jobs,
  but cannot provision VMs, read the organization's Actions billing warnings,
  change repository variables, or register runners. These require separate
  authorized administrative action.
- This opt-in changes **only** the dedicated PR #949/#950 workflows. Existing
  general source-org workflows still require their own GitHub-hosted minutes
  or approved runner adaptation. A green focused gate alone is insufficient
  for merge if repository-required checks remain failing.
- Runner labels do not themselves enforce host security. Never use a persistent
  shared machine that could retain private source or later run malicious code.
- Draft PRs remain unmerged until actual executed, reviewed, exact-head
  evidence is available. Merge #949 first and revalidate/rebase #950.

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
2. Require branch protection/rulesets for both integration refs:
   restrict who can push, require reviewed workflow changes, disable force
   pushes, and restrict deployment/runner administrators. **The workflows
   live in mutable feature branches**, so workflow-file code review matters
   even though their current YAML has job-level safeguards.
3. In the **private source repository**, navigate to **Settings > Actions >
   Runners > New self-hosted runner** and follow the official instructions
   for a verified runner package. Prefer GitHub's **just-in-time (JIT)
   registration** to guarantee at most one job per registration. Otherwise
   use repository-scoped registration with `--ephemeral`. Obtain any
   short-lived registration token via the administrative interface only;
   never commit, print, archive, or put it in Actions logs or shell history.
   For JIT registration see:
   https://docs.github.com/en/rest/actions/self-hosted-runners
4. Attach the runner **at repository scope**, with the exact additional label
   `oreslang-private-ephemeral` and the default `self-hosted`, `linux`,
   `x64` labels. Run as a dedicated non-root account. Deploy each worker in
   a **fresh isolated VM**, enforce its destruction after exactly one job,
   and provision new workers for focused Maven, full Maven and each AOT/hybrid
   job. A runner's label or deregistration alone is not VM isolation.
5. Freeze and review the target commit. In **Settings > Secrets and variables
   > Actions > Variables**, set **both** repository variables:
   - `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA`: exact full 40-character
     head SHA of the reviewed PR #949 **or** #950 source branch (one at a time).
   - `ORESLANG_PRIVATE_EPHEMERAL_RUNNER`: exact string `true`, only after
     runners are online and the approved SHA is set. Otherwise leave it
     unset/`false`; the workflow defaults to `ubuntu-24.04`.
   All jobs are skipped before checkout in self-hosted mode unless
   `github.sha` matches the approved SHA, the private repository identity
   matches, and the exact branch ref matches. When switching between PR #949
   and #950, update the approved SHA **first** and provision new workers.
   A new commit invalidates the previous approved SHA; do not silently
   authorize subsequent pushes. On unapproved SHA in runner-opt-in mode,
   all executable JVM/native jobs are ineligible for runner admission, and
   a separate `deny-unapproved-runner` job fails the overall workflow
   **on a hosted runner without checkout**. This is necessary because
   conditional *skipped* required checks can otherwise be treated as
   passing by GitHub; never interpret a skipped test job as verification.
6. **Re-run the failed workflow for the already-approved exact SHA**, after
   runner provisioning. Do not push an extra commit solely to activate a
   runner, because it changes the SHA and invalidates approval. Note that
   `workflow_dispatch` may not be available for workflows that have never
   been added to the default branch; use existing trusted push-run reruns
   rather than relying on a manual dispatch from a feature-only workflow.
   A rerun must demonstrably use the fresh approved runner; if variable
   changes are not reflected on reruns, return to the reviewed branch
   workflow change process instead of weakening the SHA gate.
   After checkout, a second assertion checks `git rev-parse HEAD == GITHUB_SHA`.
   No arbitrary `pull_request`, `pull_request_target`, forks or
   unreviewed workflow inputs may run on this worker.
7. Verify that **steps actually executed**. A run marked `failure` with no
   step records is runner infrastructure failure, **not** a compiler or
   JUnit failure. For PR #949 require focused Future/channel/host-listener
   tests and native AOT/hybrid output `first:7:9`. For #950 additionally
   require typed Future/select, ownership and readonly regressions, full
   Maven `verify`, and AOT/hybrid output `future:4`.
   Record Git head/tree SHA, JDK/GraalVM version, test counts, stdout and
   final conclusions on each PR. Test evidence belongs to the **exact
   source head**, not an older or partial mirror.
8. When verification is finished, reset the repository variable to
   `false` (or delete it), delete the approved SHA variable, stop/dispose
   of all ephemeral runner VMs, revoke
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


### Additional hardening

- The dedicated workflows intentionally **disable Maven dependency caching**
  in the setup action. An ephemeral worker may use only its disposable local
  cache for the duration of a job. Do not restore workspace archives or
  build caches from lower-trust branches or public storage.
- Self-hosted runner registration and VM image provisioning must have
  independent change review. Forward operational agent logs to a restricted,
  private diagnostics sink if needed, with secret redaction and defined
  retention. Do not upload source, test artifacts or runner workspaces to
  public buckets.
- A repository variable is **not a cryptographic authorization token** and
  a mutable workflow could modify or remove its own guard. Protected
  branches, trusted maintainers and ephemeral VM isolation remain mandatory.
- GitHub's security guidance prefers JIT single-job runners; even JIT runner
  registration must be paired with new, clean host hardware/VM state:
  https://docs.github.com/en/actions/reference/security/secure-use
- If either source head changes after the approval SHA was set, stop the
  private runner jobs, re-review the resulting tree, and update the approved
  SHA; never bypass the check in YAML to make a job run.

# Private Oreslang GitHub Actions runner: emergency CI recovery

**Purpose:** Execute the reviewed exact-main, exact-candidate and publisher
validation gates in the original private Java source repository when
GitHub-hosted jobs fail before the first step. The current canonical security
integration is PR #1217; this runner path must not create a second
cross-organization source-copy mechanism.

**Do not copy private source to another repository.** The protected
`.github/private-ci-approved-repositories.txt` currently authorizes **zero**
cross-repository destinations. In particular, `zed-pkg-test/java-app` is
public and must not receive private code. Follow the public-source exposure incident
[platform-conformance #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22),
the private-carrier incident
[#77](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77),
and the runner-admission blocker
[Java #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963).

## Safe fallback configuration (repository admins only)

1. Provision a **fresh disposable Linux x86-64 VM or isolated worker**
   solely for this private repository. Never share its work directory,
   credentials, caches, or filesystem with untrusted repositories or workloads.
   Give it no cloud/admin privileges, no application secrets, and no network
   access beyond what is required for GitHub Actions, Maven dependencies,
   GraalVM/native-image setup and trusted build tools.
2. Require effective protection for trusted `main`: restrict who can push,
   require reviewed workflow changes, disable force pushes/deletions, and
   restrict runner administrators. The reviewed workflows explicitly require
   `github.ref_protected == true`; do not remove this check to accommodate an
   ineligible plan. GitHub Free for organizations does not provide protected
   branches for private repositories. Upgrade the organization to GitHub Team
   or Enterprise before treating this path as executable.
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
5. Freeze and review the target commit/tree. In **Settings > Secrets and
   variables > Actions > Variables**, set the variables required by the chosen
   reviewed workflow. For exact-main this includes:
   - `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA`: exact reviewed 40-character
     `main` commit;
   - `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE`: exact Git tree for that
     commit;
   - `ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true` only while the disposable
     runner is online.
   Candidate/publisher workflows additionally pin their trusted workflow SHA
   and exact candidate commit/tree. A new commit invalidates the previous
   approval. Never broaden an approval to a moving branch name.
   The separate deny job intentionally runs without source checkout; if hosted
   minutes are exhausted it may itself be zero-step, which is still a fail-
   closed result rather than test evidence.
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
   step records is runner infrastructure failure, **not** a compiler or JUnit
   failure. For exact-main require focused JUnit, a fresh full Maven
   `clean verify`, stress replays and native AOT/hybrid/isolate guest
   execution. For exact-candidate/publisher validation require the workflow's
   exact reviewed candidate SHA/tree, complete declared test inventory and
   bounded identity receipt. Record Git head/tree SHA, JDK/GraalVM version,
   test counts, stdout and final conclusions. Evidence belongs only to the
   exact source head tested.
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
- This opt-in applies only to the reviewed private exact-main/candidate/
  publisher workflows. General source workflows still require hosted minutes
  or separately reviewed runner adaptation. A green focused gate alone is
  insufficient for merge if repository-required checks remain failing.
- Runner labels do not themselves enforce host security. Never use a persistent
  shared machine that could retain private source or later run malicious code.
- Security and compiler PRs remain draft/unmerged until actual executed,
  reviewed, exact-head evidence is available.


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

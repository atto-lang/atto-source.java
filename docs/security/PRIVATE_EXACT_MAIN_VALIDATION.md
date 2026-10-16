# Exact-main private self-hosted validation (no cross-org source copies)

Workflow: `.github/workflows/private-exact-main-validation.yml`.

This is a **review-required opt-in alternative** for validating private Oreslang
`main` when GitHub-hosted Actions jobs cannot obtain minutes. It runs only in
`ores-truffle-oreslang/oreslang-source.java`; no external repository or
personal access token can checkout private source. It does not change
`.github/private-ci-approved-repositories.txt`, does not approve a destination,
and is not permission to bypass issue #991 or security review of PR #992.

## Private-repository branch-protection availability

**Preflight result on 2026-10-16:** GitHub returned `protected: false` for
`main`. Reading the repository ruleset API returned HTTP 403 with the
message `Upgrade to GitHub Pro or make this repository public to enable this
feature.` Reading classic branch protection returned HTTP 403
`Resource not accessible by integration`; the latter is an integration
permission error, **not** independent evidence about configured rules.

GitHub's documented feature availability requires a qualifying plan to
protect private repository branches or apply private repository rulesets.
For an **organization-owned private repository**, an organization
administrator should verify eligibility for GitHub Team or Enterprise
before proceeding. **Do not make private source public** to unlock a
feature. If the organization's plan is ineligible, request an authorized
plan change; do not strip `github.ref_protected` from the reviewed workflows,
pretend that a CODEOWNERS file itself enforces approvals, or bypass the
reviewed trust policy to copy source to a different organization.

After the account owner confirms eligibility, an authorized repository
administrator must configure and enforce protection for `main` in GitHub:
require independent PR/code-owner approval, prevent unreviewed direct pushes
and bypass, and require **executed** status checks only after runner
eligibility is restored. Verify that the GitHub branch API reports
`protected: true`, and independently verify effective settings as an
authorized admin, **before** merging or dispatching this CI workflow.

References:
- https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches
- https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets

## Administrator setup

1. Before enabling either private runner, protect the trusted `main` branch
   using branch protection or a ruleset that **requires independent code-owner
   reviews**, blocks direct unreviewed pushes, and prevents bypass by workflow
   maintainers. Both workflows now require GitHub's `github.ref_protected`
   and the runner's `GITHUB_REF_PROTECTED=true`. A private repository
   with an unprotected `main` must fail closed even if the exact SHA, tree
   and runner variables appear correct.
2. Independently review and merge the workflow. Register a **single-use,
   ephemeral, repository-scoped** self-hosted Linux x64 runner named/labelled
   `oreslang-private-ephemeral`. Do not use a shared organization runner or
   run unreviewed PR code on it. Provision JDK 25/GraalVM-compatible Linux,
   Maven, Python 3 (standard-library XML report verifier) and enough disk/RAM
   for three sequential native builds. Provision
   without printing registration tokens or retaining build artifacts/secrets.
3. Resolve the exact reviewed `main` commit and its tree from an authenticated
   trusted checkout:
   `git rev-parse HEAD` and `git rev-parse HEAD^{tree}`.
   The reviewed workflow now requires the original numeric GitHub repository
   ID `1400792274` as well as the exact repository name and private status;
   a repository recreated under an old slug must fail authorization.
   An administrator must explicitly set all **repository variables**:
   `ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true`,
   `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA=<40-character main commit>`,
   `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE=<40-character Git tree>`.
   Do not put tokens or secrets into these variables.
4. In the original private repository's Actions tab, dispatch
   **Private exact-main Java/Native validation**, selecting `main` only.
   Unapproved SHA/ref/visibility/runner variable combinations fail closed.
   A source-mainline advance invalidates the approval until the commit/tree
   values are deliberately updated. The workflow checkout checks immutable
   40-hex commit/tree values, the GitHub-provided repository ID, the checked-out
   commit and tree, and a clean worktree *before* invoking the toolchain.
5. Confirm that focused JUnit, **fresh `mvn ... -DskipTests=false
   -Dmaven.test.skip=false clean verify`**, ten stress replays, native AOT,
   native hybrid, and strict-isolate guest execution all ran. The reviewed
   exact-main workflow now independently reads the full Maven Surefire
   reports: all six Future/channel focused classes and at least one
   additional class must have executed, and **every** reported test must
   be successful and non-skipped (including no flaky/rerun failure
   markers). The report directory and per-class XML must not be symlinks,
   misplaced, malformed, oversized, or inconsistent with declared counts.
   Run this verifier from trusted workflow YAML, not candidate scripts.
   This attests broader JUnit execution, not that malicious candidate
   sources could not alter which tests were discovered or built.
   Each of the three native profiles must run `mvn ... -DskipTests clean package`
   **before** its guest smoke test, rather than reusing an incremental build
   or an artifact left behind by a prior profile. Verify this requirement
   separately for the exact-main and exact-candidate workflows.
   Do not claim success from a skipped job or a GitHub Actions job with
   `steps=null`. Preserve workflow/job links and the exact commit/tree.
6. Retire the ephemeral runner and clear the approval SHA/tree and enable
   variable after the validation session. Check for lingering workspaces,
   artifacts, caches and source copies; perform the host's secure teardown.

## Shared validation queue

The exact-main, exact-candidate, and private-publisher validation workflows
all use the same GitHub Actions concurrency group:
`private-oreslang-isolated-validation` with `cancel-in-progress: false`.
These validations can otherwise overlap even when their individual workflow
groups seem exclusive, causing resource contention or accidental mixed-source
workspace evidence on repository-scoped runners. Treat this as an additional
serialization control, **not** a replacement for one-time runner provisioning,
immutable commit/tree checks, protected main, or independent approval.

GitHub concurrency permits at most one running and one pending run per group.
A newly queued dispatch can replace an older pending run even when running
runs are not canceled. Recheck the exact requested candidate SHA and job
status after admission; a superseded or canceled pending run is not evidence
of test execution. Do not rely on ordering between queued dispatches.

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

## Pre-merge candidate verification without cross-org copies

The original `private-exact-main-validation.yml` intentionally checks **only**
a reviewed mainline commit. It cannot validate an unmerged permissions PR.
The separately reviewed `.github/workflows/private-exact-candidate-validation.yml`
permits **only a manually approved immutable commit in this same private
repository** to run on a fresh repository-scoped disposable runner. The workflow
definition itself MUST already have been independently reviewed and merged
into source `main`; no `pull_request` or `pull_request_target` event executes
candidate-provided workflows.

Before each run, an administrator/security owner must independently review the
candidate branch diff and explicitly set repository variables (not secrets):

- `ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true` only while a dedicated **single-use**
  repository runner is provisioned.
- `ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA=<reviewed current main workflow commit SHA>`
  pins the **trusted workflow definition**. A mainline change invalidates
  the approval.
- `ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA=<reviewed candidate 40-hex commit SHA>`
  identifies the **one** source-repo candidate authorized to execute.
- `ORESLANG_PRIVATE_CANDIDATE_APPROVED_TREE=<Git tree SHA of that exact candidate>`
  binds the complete immutable candidate contents.

On trusted main, dispatch **Private exact-candidate Java/Native validation**
with `candidate_sha` matching the approved candidate SHA. The workflow
rejects other repositories, repository IDs, visibility, unprotected refs, workflow commits
or candidate SHAs *before checking out any candidate code*. Its pinned
read-only checkout then requires the selected commit, Git tree, and clean
worktree to match the approvals before GraalVM setup or Maven/guest execution.
The workflow runs the **six-class** focused native-carrier suite:
`DenoCliDefaultPermissionsTest`, `RuntimePermissionsNetworkAuditTest`,
`RuntimePermissionsTest`, `RuntimePermissionsExplicitPolicyTest`,
`IsolatePolicySerializationAuditTest`, and `ScopedPermissionInteropTest`.
Before focused Maven runs, the **trusted workflow** rejects symlinked
`target` or Surefire paths and removes any existing `target/surefire-reports`
output. This prevents stale or candidate-supplied XML reports from being
accepted as fresh test execution evidence. Other `target` artifacts are not
removed; a disposable runner and independent source review remain mandatory.
After the focused Maven invocation, the **trusted workflow** runs a
Python 3 standard-library verifier over Maven Surefire XML reports. It must
find at least one executed, non-skipped, non-failing test case in **each**
of those six classes before the full build is admitted. Missing reports,
skipped-only classes and malformed XML fail closed. As this is a single-module
Maven project, evidence must come from **only** `target/surefire-reports`;
nested fixture files and symlinked report paths are rejected. Each required
fully qualified test class must produce its own `TEST-<classname>.xml`,
with a matching `testsuite` name, direct `testcase` children whose class
names match, and a declared test count equal to the actual children.
The verifier independently requires aggregate `failures`, `errors`, and
`skipped` counts to be zero, rejects contradictory report totals, and
rejects flaky/rerun failure markers even if the final status is passing.
Empty, malformed, skipped-only, failed, or oversized (>16 MiB) evidence is
rejected. A trusted workflow step first clears any pre-existing report
directory; reports may never be supplied by a candidate's checked-out tree.
This check is embedded in the reviewed workflow definition rather than loaded as a script
from the untrusted candidate source checkout. The administrator's isolated runner
must provide `python3` in addition to Java, GraalVM and Maven.

Both trusted **focused** Maven stages (Future/channel exact-main and
permissions exact-candidate) now execute `mvn ... clean test`, not incremental
`mvn ... test`. Clearing Surefire XML alone is insufficient: a checkout can
contain committed or pre-existing `target/classes` or `target/test-classes`
which would otherwise invalidate exact-source testing. A fresh Maven target
ensures the compiled classes and reports correspond to the approved checkout,
subject to the separately audited Maven build configuration.

The full Java 25 `verify` stage uses `mvn ... -DskipTests=false
-Dmaven.test.skip=false clean verify` to discard focused-only reports and
rebuild from scratch. A separate trusted Surefire verifier then requires
successful testcases in all six focused classes **again**, as well as at
least one successful class outside that focused subset. **Every** full-suite
JUnit report must have consistent positive testcase counts, aggregate
`failures=0`, `errors=0`, and `skipped=0`, and no failed, skipped,
flaky, or rerun-failure testcase markers. A single failed or disabled test
blocks the evidence gate; make any intentional skip exceptions an explicit,
independently reviewed policy change rather than silently accepting them.
Missing, symlinked, oversized, mismatched-class, or malformed report
evidence also fails closed.
This is a proof of fresh **broader test execution**, not a guarantee that
every test in the source tree was discovered or that a malicious candidate
cannot alter its build/test configuration. A qualified security reviewer must
audit the candidate's Maven build configuration.

Each native AOT, hybrid, and
strict-isolate stage separately runs `mvn -Pnative-... -DskipTests clean package`
before executing its smoke test. This prevents a stale binary or shared
library left by another profile (or provided in the candidate checkout) from
being mistaken for the tested output. Both strict-isolate stages also require
**exactly one**, non-empty, regular (not symlinked) `target/oresvm.so` or
`target/liboresvm.so` after the clean build. Missing, ambiguous or invalid
libraries fail with an explicit shell `exit 1`. Do not rely on `set -e`
alone after a shell `&&` chain: Bash can continue after a failed intermediate
condition and accidentally admit invalid artifacts. The workflow's Java
security regression executes this exact shell fragment against synthetic
filesystem fixtures, including symlinks with otherwise valid library bytes.
These clean builds are more expensive than incremental builds but are necessary
for trustworthy exact-source evidence.
Capture actual workflow job steps, exit
codes, SHA/tree prints and complete test results; **a queued, skipped or
zero-step job is never passing evidence**.

Important limitations: the approved candidate contains code that will execute
on the self-hosted runner. This workflow does **not** sandbox arbitrary
malicious candidate code and does not prove that the machine is ephemeral;
security owners must review the candidate and provision an isolated,
single-use disposable host with no unrelated credentials, local data,
long-lived tokens or access to other repositories. Administrators must
securely tear it down and clear the three candidate approval variables
and runner flag after each run. Do not add GitHub-hosted/shared runner fallback,
external source mirrors, reusable permission-approval inputs, or artifacts
containing private source. `main` branch protection and code-owner review
remain necessary before enabling this workflow.

**The workflow does not exist on source main until independently reviewed and
merged. It is not executable merely because it appears in a PR branch.**

## Publisher regression on the source-local runner

The separate `.github/workflows/private-exact-publisher-validation.yml` adds a
protected-main, manual-only verification path for publisher PR #1018. It
requires reviewed repository variables
`ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA`,
`ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA`, and
`ORESLANG_PRIVATE_PUBLISHER_APPROVED_TREE` in addition to the
`ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true` admission gate.

An administrator must first require independent code-owner reviews on the
private source main branch, merge the reviewed workflow and configure a
single-use source-repository-scoped `oreslang-private-ephemeral` runner.
Dispatch from protected main with `candidate_sha` equal to the approved
publisher PR head. The workflow checks trusted workflow/candidate commit/tree
identity, then syntax-checks the scripts, runs the complete Bash destination
policy suite and runs all discovered Python publisher tests three times.
It rejects fewer than 57 discovered tests or any incomplete/failed run.

A successful publisher run is distinct from Java 25/GraalVM validation of
PR #1034. This workflow does not copy source to another organization or
authorize any mirror destination. Any transfer still requires independent
approval through the protected-main private destination policy.

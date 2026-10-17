# Private-CI policy + source-local runner reconciliation (draft)

This is a **current-main-based integration candidate only**. Neither the files nor the branch authorize private-source export, activate a runner, or permit a merge without independent review.

## Source lines / decisions

- Start from immutable Java main commit `12cdd17af7f949a3c54e045a46a2fc21be0ea5d0`, tree `939c88979417da9c0b4bc7bcf045c82e2e477483`.
- Preserve the comprehensive fail-closed publisher, exact source snapshot approval policy, CI preflight and 150 checked-in Python adversarial tests (147 inherited plus 2 integration regressions) from Java security PR #954, commit `b9ae8b0b350f73f2e643a45400abe245f6430aad`. Preserve the existing executable modes for existing scripts.
- Source the three workflow-dispatch-only, source-repository-scoped exact-runner workflows and their JUnit workflow admission tests from Java #1014, commit `abccfd2c77ff4ae30e57ab38780372170150d4d8`.
- Union existing, #954, and #1014 CODEOWNERS entries without dropping protections. No ACL or GitHub branch setting is changed by a CODEOWNERS file.
- Related #1018 numeric identity binding, #1089 protected-source checks, and #1118 exact Git tree/TOCTOU protections overlap #954's comprehensive implementation; compare adversarial coverage rather than overwriting the publisher with a shorter, less-protective version.

## Mandatory integration security invariants

1. Source repository identity and visibility; protected `main`, effective required reviewer/CODEOWNER and strict status checks must be established independently.
2. Publisher code and approval files must match their protected-main Git blobs; exact source commit/tree and approved snapshot tuple are required **before reading private bytes**.
3. Destination numeric repo ID must match independently approved policy and remain private and active on every write; policy must be revalidated at the source. Tree/blob integrity must be independently verified before ref publication.
4. The destination and source snapshot approval files intentionally have **zero active entries**. Never use a draft policy to authorize an export.
5. Source-local workflows cannot run private candidates unless dispatched from protected, approved main with exact workflow, candidate commit, tree, and disposable repo-scoped runner controls.

## Integration test requirements (not yet claimed passed)

- `bash -n scripts/check-private-ci-destination.sh scripts/test-private-ci-destination.sh scripts/run-in-private-ci-destination.sh`.
- `python3 -I -m py_compile scripts/publish-private-ci-source.py scripts/test-publish-private-ci-source.py`.
- `bash scripts/test-private-ci-destination.sh`.
- Three runs of `python3 -I scripts/test-publish-private-ci-source.py`, with at least 150 tests each; no skips/failures/expected failures.
- Compile and run the three source-runner JUnit workflow/security tests from #1014; full JDK 25 Maven and GraalVM AOT/hybrid guest execution on exact checked-out tree after independently approved source-local runner is functional.

## Release hold

`main.protected=false` currently; source org Actions jobs are failing before runner allocation; no independent PR approvals; source export destination allowlist and exact source-snapshot approval both empty; P0 prior public source exposure is not contained. Leave draft. Do not copy private source to a funded organization or merge this branch based on source-free tests.

## CODEOWNERS terminal-rule regression found and hardened

The first integration commit sorted the combined CODEOWNERS paths alphabetically,
but the inherited protected-source publisher requires its exact
`REQUIRED_CODEOWNER_PATHS` block at the **end** of the effective CODEOWNERS
entries, in that same order. This integration mismatch would have rejected an
otherwise approved protected-main policy. The fixed integration synchronizes
all **18** source-export, approval, CI-workflow, documentation and admission
test paths between the publisher's mandatory terminal block and the actual
CODEOWNERS file. Two checked-in Python regressions now check the real file's
terminal block and mandatory coverage of source-local runner files.

The source-local publisher validation workflow now requires a minimum **150**
Python tests in each of three rounds; a passing command exit without the
required count, or any skipped/expected failure, is rejected.

### Independent source-free GitHub Actions evidence

The separate **private** funded carrier
`scintilla-run-test/gleam-runner-conformance` ran 20 independently authored
synthetic CODEOWNERS terminal-rule cases three times (60 passing case
executions). Hosted run:
https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/38990669363

The carrier test tree contains **no private Oreslang publisher, runtime,
compiler source or policy bytes**. Its proof is limited to the synthetic
fail-closed terminal-rule algorithm. It is not a successful execution of
the source repository's 149 regression tests or any GraalVM/JDK test.

After the latest hardening commit, source private CI again failed before job
steps were executed, including preflight run
https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/38990565466
(0 steps). The exact-head source tests remain unverified. Do not create
a confidential cross-organization Git mirror unless protected-main approval,
reviewed destination and source-snapshot tuple, and incident-containment gates
are independently demonstrated.

## Untrusted candidate Python import-path hardening

The three private source-local workflows and the policy preflight now run
Python-based report verifiers, script compilation, and publisher tests with
Python `-I` isolated mode. This prevents an untrusted candidate directory from
silently replacing the verification script's standard-library imports through
the current working directory or environment-provided PYTHONPATH. The checked-in
Python suite includes a new admission regression enforcing isolated-mode
call sites, raising the required case count to **150**. Exact-head execution
on original-source CI remains blocked pending runner allocation and protected
main approval. Do not mistake a source-free hosted mock for candidate JUnit,
GraalVM, or publisher test evidence.


## Follow-up: fail-closed descriptor flag admission and exact-run parity (2026-10-16)

- The previously reviewed publisher checked only `hasattr(os, O_NOFOLLOW/O_DIRECTORY/O_NONBLOCK)`.
  A defined but **zero-valued** or non-integer flag bypassed this availability
  gate even though the required no-symlink, directory and nonblocking open
  protections were absent. A shared `safe_descriptor_flags_available()`
  predicate now rejects missing, zero, negative, boolean and non-integer flags.
  It is used by the approval-policy read, the trusted publisher checkout
  attestation and the descriptor-relative source snapshot collector.
- Added two unprivileged publisher test methods that simulate missing/zero
  protection flags and require all three admission boundaries to reject
  **before an unsafe `os.open`**. The expected checked-in Python test suite
  is now **152 cases** and the trusted publisher workflow rejects lower counts
  during each of its three required isolated replays.
- A focused isolated Python 3 smoke executed 3 tests including 15 invalid flag
  substitutions successfully. The 152-case original-source Python suite was
  **not executed** and must not be called green.
- Read the actual protected-candidate workflow YAML from this branch and
  exhaustively compared the inverse admission expressions: 512 exact-main,
  2,048 exact-candidate and 2,048 exact-publisher Boolean assignments, **zero
  allow/deny contradictions**. This is structural expression verification,
  not GitHub Actions execution or runner-policy authorization.
- Security hold remains: source `main` reports unprotected, the destination
  and snapshot allowlists remain empty, and
  `oreslang-platform-conformance#22` (private source exposed on a public
  carrier) is open. Do not transfer private source to a new organization
  or mark this PR merge-ready without independent incident containment and
  CODEOWNER/admin review.

## Distinct descriptor-mask hardening (2026-10-16)

- Positive integer flags alone are insufficient to establish secure
  `open()` admission. A platform shim which aliases `O_NOFOLLOW`,
  `O_DIRECTORY`, or `O_NONBLOCK` to another flag may pass presence
  checks without enforcing the expected independent restrictions.
- `safe_descriptor_flags_available()` now rejects **overlapping** masks
  as well as absent/zero/negative/noninteger flags. One additional
  checked-in regression covers three aliases and a combined mask, and
  asserts both protected-policy and source-snapshot admission stop before
  opening any file.
- Publisher test module now declares **154** adversarial cases. The
  protected source-local publisher workflow requires at least **154**
  in each of three fully executed, no-skip replays. Full original-source
  execution remains blocked by the unprotected source main, empty approval
  files and unavailable source-org Actions capacity; isolated bitmask tests
  must not be labeled end-to-end publisher certification.

## Exact-tree carrier branch hardening (2026-10-16)

- Publisher `publish()` now rejects any destination branch not named exactly
  `ci/oreslang-<lowercase 40-hex independently approved source Git tree SHA>`.
  A generic branch, a different tree SHA, upper-case hash, shortened hash,
  traversal suffix, or extra ref component is rejected before policy API reads,
  private source reads, or Git writes. This is an additional provenance check,
  **not** independent authorization to export source.
- All existing mocked destination-ref and end-to-end publisher tests now derive
  their branch from each test's actual expected tree (including cases that
  mutate the expected fixture tree). The new negative test checks source-free
  pre-policy failure for mismatches. The test module now declares **153 cases**;
  the trusted source-local publisher workflow requires at least 153 and
  replays the entire suite three times without skipped tests.
- The independent, non-Oreslang-source GitHub-hosted capacity probe
  [Scintilla run 38994844950](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/38994844950)
  completed successfully, including 15 source-free exact-tree branch-name
  assertions. This **does not run** the original publisher's 153 Python
  regressions, does not prove Maven/Native Image, and does not authorize
  any private cross-org Git tree transfer.
- Production test gate remains blocked by unprotected source main, empty
  destination and snapshot allowlists, missing independent reviewer sign-off
  and zero-step source organization Actions runs. Do not mark ready or merge.

## Source-local actor candidate runner handoff (October 16, 2026)

This is a **draft code-review handoff**, not permission to activate a runner,
merge protected workflow code, publish private source, or assert tests passed.

The trusted `.github/workflows/private-exact-candidate-validation.yml`
workflow now runs three required actor regression JUnit classes on the exact
independently approved candidate commit:

- `dev.oreslang.runtime.PersistentActorMapTest` — persistent HAMT invariants
- `dev.oreslang.runtime.ActorSharedLocalMemoryTest` — SHARED actor-local
  owner-bound heap and carrier authority
- `dev.oreslang.types.ActorStateModeContractsTest` — actor-state contract

It deletes stale focused Surefire reports, executes the three classes with
JDK 25/GraalVM and tests enabled, rejects missing/failed/skipped/flaky or
misidentified direct JUnit cases, then requires those same classes again
in the fresh full `mvn clean verify` report set. The separately protected
`PrivateExactMainValidationWorkflowSecurityTest` statically checks these
required workflow gates and the final identity-only artifact step.

Only **after** the full candidate suite and AOT/hybrid/isolate guest checks
succeed, the job writes a bounded metadata-only
`private-candidate-identity-<run_id>-<attempt>` artifact (3-day retention).
It contains source repository ID, trusted workflow commit, candidate commit
and Git tree, run ID/attempt and expected test classes; it has explicit
`verified_execution_claim=false` and
`native_guest_parity_certified=false`. Neither raw private source nor
compiled binaries nor Surefire XML are uploaded. Independent reviewer must
check GitHub run/job/step and JUnit logs; an artifact by itself is **not**
runtime or release certification.

### Explicit Option-pattern candidate verification (October 17, 2026)

The exact-candidate source-local workflow now provides an additional **opt-in**
`workflow_dispatch` boolean `verify_option_pattern` (default **false**).
It does not run private source on an untrusted hosted carrier and cannot be
used without the workflow's existing reviewed protected-main, numeric source
repository ID, exact candidate commit/tree and disposable-runner admission.

For an independently reviewed candidate containing the full Option feature
branch ([Java #1121](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1121)),
the reviewer/administrator must dispatch the pinned workflow on `main` with
`candidate_sha=<reviewed #1121 immutable head>` and
`verify_option_pattern=true`. The branch must be re-reviewed and its
approved SHA/tree repinned if it changes. The administrator must also set
`ORESLANG_PRIVATE_CANDIDATE_OPTION_TEST_BLOB` to the independently reviewed
Git blob of `src/test/java/dev/oreslang/OptionIfMatchesTest.java`; for the
current #1121 head that blob is
`bae3a3f5506c5420b08c147610dba0d0b92ca7b5`. A missing, malformed or
different blob fails **before candidate checkout** when Option verification is
requested, and the checked-out test file is rehashed again before Maven runs. The security-integration candidate
itself need not contain Option fixtures and may use
`verify_option_pattern=false`; **such a run MUST NOT be reported as
Option-conformance evidence**.

When requested, the trusted workflow verifies the real candidate contains
`OptionIfMatchesTest.java`, the shared Option manifest and checker; discards
the previous actor-memory Surefire reports; executes the JUnit class under
GraalVM JDK 25 with native carriers and skip flags disabled; and checks a new
Surefire XML with exactly the **13 named** reference methods, no missing,
duplicated, failed, errored, skipped, flaky or rerun cases. It then requires
the Option class again in the **fresh full** Maven `clean verify` report set.
The same opt-in gate also pins the **exact** shared v1 Option contract
(`bb2302e138f4b36d68afd6ffa9280b24fa270d04`), admission checker
(`77a30027582d2496d5e7087a9a51a75c075496cd`) and Java process probe
(`6ef4692b0d6c30e572d0f5520e2dd29ad68932cf`) by their actual Git
blob identities. It runs all **nine** source admission cases and four
positive Graal guest exit values (42, 7, 43, 0) on the exact candidate.
An independent Python verifier embedded in the trusted protected workflow
rejects missing/duplicated cases, incorrect compiler exits and guest results,
a forged suite/revision or an incomplete report. The candidate's own
`scripts/check_option_pattern_contract.py` cannot silently replace that
trusted report verifier.

This only proves JUnit and source/guest execution when corresponding GitHub
job steps and test logs independently agree with the immutable candidate
tree. AOT/hybrid and strict isolate validation remain mandatory and separate.

The final identity-only artifact now records
`option_pattern_requested=true/false` and
`option_pattern_junit_methods_required=13/0`, but still explicitly has
`verified_execution_claim=false` and
`native_guest_parity_certified=false`. No raw source, binaries or Surefire
reports are uploaded; approval and execution must be independently audited.
`PrivateExactMainValidationWorkflowSecurityTest` also checks the opt-in
workflow guards, ordering, fresh report enforcement and artifact honesty.

**No source-local Option suite or JDK 25 runtime test has executed on this
integration head.** The private-source export allowlists remain empty; no
cross-organization source transfer is authorized.

### Administrator-controlled prerequisites (not satisfied by this PR)

1. Address open private-source exposure incident
   `oreslang-platform-conformance#22` and obtain independent incident-owner
   containment evidence. Do not use the previously public carrier or assert
   that merely hiding a branch restores historical confidentiality.
2. Configure **enforced** Java source `main` protection and required
   independent CODEOWNER review. Review this PR's trusted workflow, runner
   conditions, admission test and evidence metadata before merging it
   under protected-main policy. CODEOWNERS text alone cannot protect a branch.
3. Register an isolated, disposable, repository-scoped Linux x64 self-hosted
   runner matching labels `self-hosted, linux, x64,
   oreslang-private-ephemeral`. No org-shared host or GitHub-hosted fallback,
   reused working directory, repository secrets or third-party private mirror.
   Ensure source-local network egress and runner lifecycle are independently
   audited.
4. From **reviewed protected main**, independently pin repository variable
   `ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true`,
   `ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA=<reviewed protected-main SHA>`,
   `ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA=<exact approved actor PR commit>`,
   `ORESLANG_PRIVATE_CANDIDATE_APPROVED_TREE=<Git tree of that commit>`.
   The branch/ref and exact tree must come from a trusted independent review,
   not unreviewed PR-supplied input or candidate-controlled GitHub Actions logs.
   Keep the cross-org destination and snapshot export allowlists **empty**.
5. Dispatch the approved workflow on `main` with
   `candidate_sha=<exact approved actor PR commit>`. Confirm the positive
   gate assigned the repository-scoped disposable runner and the denial
   gate was skipped. Verify checkout HEAD/tree, all focused actor classes,
   fresh complete JUnit reports, AOT/hybrid/isolate guest execution, clean
   job completion, run attempt and metadata-only artifact *independently*
   from GitHub's API/job logs before considering the snapshot tested.
   Re-review and reapprove the exact tuple whenever the candidate head moves.

As of this audit, all five source `main` branches are reported unprotected,
the two publisher/export approval files remain empty, the exposure incident
is open, and sampled private source workflows end with **zero executed steps
and zero artifacts**. The source-local candidate workflow itself has **not
executed**. A successful source-free test carrier neither unlocks this
candidate workflow nor authorizes confidential Git-tree mirroring.

## October 17 follow-up: child environment transport/config hardening

The protected publisher now treats caller environment as untrusted not only for
shell/Git/loader injection, but also for authenticated GitHub CLI transport and
configuration. `verified_child_env()` strips upper/lower HTTP(S)/ALL/NO proxy
variables, custom TLS/CA/key-log variables, GitHub CLI config/debug/server
overrides, XDG config selection and Go TLS debug state. It forces
`HOME=/nonexistent`, `XDG_CONFIG_HOME=/nonexistent`,
`GH_HOST=github.com`, a fixed system executable path and disabled Git global
and system configuration. Only `GH_TOKEN`/`GITHUB_TOKEN` are intentionally
preserved for authentication.

The existing publisher adversarial suite remains **154 declared tests**; its
child-environment regression was expanded to seed proxy, CA, `GH_CONFIG_DIR`,
host/API overrides, shell hooks, loader injection and Git overrides together,
then assert all are removed/overridden while only the authentication tokens
survive. A source-derived focused logic execution confirmed no injected
transport/config variable survives. The exact full 154-test checked-in suite
still has **not** executed on the source repository because Actions jobs fail
before runner allocation; do not call this CI green.

A fresh source-free alternate-org capacity probe also succeeded:
[Sagitta #39043359219](https://github.com/sagitta-stack/sagitta-infra/actions/runs/39043359219)
proved GraalVM JDK 25 + Maven + public Truffle dependency/JUnit capacity, and
[Sagitta #39043574704](https://github.com/sagitta-stack/sagitta-infra/actions/runs/39043574704)
additionally built and executed a **source-free GraalVM Native Image** smoke.
These contain no Oreslang source and authorize no private export. The separate
source-free exact-tree gate
[dummy-stack #38988696252](https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-api-server.ores/actions/runs/38988696252)
still proves synthetic branch/tree matching and sensitivity to changed content
or file modes.

The confidential source transfer hold remains unchanged: source `main` is
unprotected, protected-main destination/snapshot allowlists are empty,
independent review is absent, and the P0 prior exposure incident remains open.



## October 17 follow-up: deterministic GitHub auth token selection

The publisher no longer leaves GitHub CLI authentication dependent on the CLI's
environment-variable precedence. If both `GH_TOKEN` and `GITHUB_TOKEN` are
present and differ, authorization fails before any child process or GitHub API
request. A single token, or two identical token variables, is normalized to
`GH_TOKEN` and `GITHUB_TOKEN` is removed from the child environment.

This closes a confused-principal edge where a stale or caller-controlled
`GH_TOKEN` could otherwise override the workflow's intended
`GITHUB_TOKEN`. Regression coverage exercises conflicting, identical and
single-token environments without logging token material. This change does not
authorize source export: protected main, independent review, allowlists and the
existing confidentiality hold remain mandatory.

## Draft fail-closed exposure-incident gate (2026-10-17)

The protected private-source publisher must not infer that changing the previously
public carrier to `private` restores historical confidentiality. The exposed
objects can survive in earlier history, cached references, public forks or clones.
The exposure incident `oreslang-platform-conformance#22` is still OPEN.

This review candidate adds an **additional necessary, not sufficient** export
admission check. Before collecting private source and again ahead of each Git
object/ref write, the authenticated GitHub API must confirm the incident's
pinned repository/issue identity, `state=closed`, `state_reason=completed`,
and a nonempty UTC `closed_at` value. Any missing authorization or private
cross-repository metadata permission fails closed. Reopening the issue between
writes must stop the next upload. No bypass flag or unsafe fallback is added.

The publisher's source-main protection, independently approved destination ID,
immutable source commit/tree tuple, trusted checkout, protected carrier main,
CODEOWNER review, exact validation-workflow blob, and per-write approval checks
remain mandatory even after incident closure. The API budget reserve increases
by one read per write, from `25 * estimated_writes + 160` to
`26 * estimated_writes + 160`.

The publisher adversarial suite now declares **162 test methods**, with three
new tests for open, spoofed/malformed, and reopened incident state. Both source
preflight and private publisher validation workflows require at least 162 tests
in each of three complete no-skip rounds. **The source CI has not executed
these tests yet**, so source-local runner certification and any merge remain
blocked. The independent funded-organization synthetic policy-model run
[Scintilla #39051965620](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/39051965620)
passed 27 source-free tests three times, including 8,192 synthetic authority
assignments per round. That run does **not** contain or test the private
publisher and does not authorize source export.

An independently approved read-only GitHub credential able to inspect the
private conformance incident is necessary for this extra publisher admission
query; never include credentials or private source bytes in logs/artifacts.

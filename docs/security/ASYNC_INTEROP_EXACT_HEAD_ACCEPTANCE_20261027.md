# Java Task/Future/one-shot Observable: source-org runner acceptance

Status: **DRAFT ACCEPTANCE PLAN**. A source-free runner executing code is a
prerequisite, not JUnit proof. Do not export this private source to another org.

## Approved execution topology

Use one fresh, disposable worker assigned to the Oreslang **source org**. Its
runner group must restrict repository/workflow access to the intended workflow,
have no production secrets/credentials, and have no network route while
compiling or executing untrusted branch contents. Prewarm *verified* Maven
dependencies and JDK 25 / GraalVM in a separate trust stage with no PR source.
Use a dedicated unprivileged OS user / VM or equivalent isolation. Disable
persistent caches/artifact uploads and delete the worker after the job.
Prefer native read-only source checkout, no persist-credentials. A self-hosted
runner with unrestricted network, write token, production secrets, or access
to other private checkout data is **not an acceptable shortcut**.

A source-org local runner needs no cross-organization source export. **Do not**
alter the private-source mirror allowlist to solve missing Actions minutes.
Source `main` must be protected and an independent `@ORESoftware` review must
approve the reviewed carrier contract before *any* cross-org export. A token-
free GitHub-connector tree copier is still a source export.

## Proof procedure

1. Admin confirms an actual source-free canary ran **more than zero steps** on
   the intended runner, and records the exact run URL, runner image/JDK manifest.
2. Freeze Java draft PR #2153's exact head SHA and confirm the entire
   checked-out tree is clean. No merges or resets while executing proof.
3. Execute, in an externally enforced disconnected source sandbox with
   credential-free environment and cached Maven repository:

   ```sh
   bash tools/verify-async-interop-exact-head.sh FULL_40_HEX_PR_HEAD_SHA
   ```

4. Require full JUnit XML and real test counts for:
   `OresFutureObserverTest`, `TaskFutureAdaptersTest`,
   `OneShotObservableTest`, `FutureFromInlineTaskLanguageTest`.
   A green build with missing targeted tests is not proof.
5. Run a separate full `mvn -B -o -ntp verify` and native-image smoke, after
   explicitly reviewing their entry points, with the **same** exact Git tree.
   The focused JVM runner script does **not** certify Native Image.
6. Attach immutable: source commit SHA, root-tree SHA, runner provision record,
   JDK/GraalVM version, executed step count, test XML, process exits,
   cancellation/retirement trace, source diff and independent reviewer signoff.
   CI tests failing with zero steps are infrastructure admission failures.

## Mandatory adversarial cases

- Terminal producer before observer registration (success, real cancel,
  ordinary user-raised CancellationException, failure, void/null)
- Observer cancel vs producer settle racing, including 128+ interleavings
- Two independent non-owning observer views; neither can cancel producer
- Tracked adapter `getDone()` cannot certify independent producer retirement;
  an in-flight producer must remain live until its *own* `getDone()`
- Losing/invalid registration can never publish provisional success
- Failure to admit a tracked Task cleans up any privately owned subscription
- Duplicate subscription prohibited for non-Copy/affine values; current host
  one-shot helper is **not** guest ownership evidence
- Cross-actor generation swap and stale callback rejection must be verified
  before any source-facing `Observable.from_future` API is enabled
- `#first` returns Option, `#single` rejects zero/2+ emissions. Current
  one-shot substrate cannot prove a general multi-event Observable

## Release decision

Keep Java PR #2153 and conformance PR #192 DRAFT until exact-head JVM,
native-image, source fixture and independent five-backend parity gates all
have nonzero executed-step evidence. All 62 shared vectors remain PENDING.

## 2026-10-27: actual executable-step discovery and fail-closed JUnit receipt

The new source-only `.github/workflows/async-interop-source-org-runner-canary.yml`
targets `[self-hosted, linux, x64, oreslang-isolated-canary-2172-v2]` with
`permissions: {}`. Its single step **never checks out, fetches, or executes
the Oreslang private source**. It merely identifies installed runtime tools
and prints an explicitly source-free receipt. A queued canary is NOT a proof
of runner availability; require at least one successfully executed step and
independent administrator confirmation of disposable isolation, source-org
runner-group restrictions, offline build execution, and the absence of
production secrets. GitHub Actions job permission settings alone do not
prove OS/process isolation.

The exact-head script now executes `clean test` in offline Maven mode so
stale class files or reports cannot satisfy the gate, and executes
`tests/test_verify_async_interop_junit.py` first. The Python suite has
10 independent source-free adversarial tests. The additional verifier
`tools/verify_async_interop_junit.py` refuses a JVM success receipt unless
these four unskipped JUnit reports contain the expected counts, identities,
and distinct method records:

| Exact JUnit suite | Required passing executions |
| --- | ---: |
| `OresFutureObserverTest` | 10 |
| `TaskFutureAdaptersTest` | 11 |
| `OneShotObservableTest` | 14 |
| `FutureFromInlineTaskLanguageTest` | 7 |
| **Total** | **42** |

A missing, symlinked, malformed, duplicate, skipped, failed, or misattributed
report denies the JVM proof. The counts are frozen to this implementation
revision and must be reviewed again if test classes change. The verifier
is a *receipt sanity gate*; it does not independently authenticate a
malicious Maven plugin, source code under test, or the runner.

**Runner action after the canary executes:** freeze the then-current PR head,
provision an administrator-verified disposable source-org-only worker with
JDK 25, GraalVM and offline-cached Maven dependencies, and invoke
`bash tools/verify-async-interop-exact-head.sh HEAD_SHA`. Collect separate
full-Maven and native-image receipts on the same pinned source tree. Never
mark Native AOT, full guest Observable syntax, or any other backend as passed
from the focused script alone.

**Privacy blocker unchanged:** the source-main private mirror allowlist is
empty and `main` has not been verified protected. A GitHub connector
Git-tree write to another organization remains a private-source export
requiring the independent policy approval, irrespective of Actions minutes.

## 2026-10-27 safety correction: DO NOT attach a runner to queued PR jobs

The first source-free canary workflow was automatically triggered via
`pull_request` and could therefore dispatch an unreviewed PR-supplied
workflow onto a self-hosted runner. Subsequent pre-fix revisions also
contained a malformed shell quote and duplicate receipt markers. Those
versions **must not** be trusted as an admission check.

**Three pre-fix runs remained queued when inspected:**

- `40546884149` (head `a8faa62d3e71e22c5b6256db99f9485cdf8c4d50`)
- `40546920891` (head `567d1e4308747e61367c4625abc37ee078b2bd92`)
- `40547417920` (head `6d335236a8c6b86326e82b8d0e44f3d750796bf3`)

**Administrator must cancel all queued pre-fix runs and verify no similar
unreviewed self-hosted jobs remain before registering any matching runner.**
An old queued workflow can execute after a runner becomes available, even
if a newer PR revision removes its automatic trigger. The GitHub connector
currently used to inspect this issue cannot cancel runs or register runners;
issue comments and draft code changes alone do not do either.

The updated canary workflow now has **only `workflow_dispatch`**, empty
job permissions, no checkout, a `refs/heads/main` guard, and Bash syntax
validated locally. It checks Java/Javac 25, Maven, Python and GraalVM
Native Image tooling. A developer cannot dispatch this draft-only workflow
safely: GitHub manual dispatch requires the workflow file to exist on
the default branch. Protect source `main`, obtain independent CODEOWNER
review and merge the *source-free* canary into the default branch under
approved governance before manually dispatching it there.

Do not merge the source-bearing implementation PR to solve that workflow
availability issue, and do not run PR-supplied workflow definitions on
self-hosted workers. Runner registration or service activation must be
performed by the authorized source-organization administrator. Any
scheduled job is separately subject to no-secrets/egress isolation checks.

The checked-in focused source-free JUnit XML validator and its tests were
reconstructed in a local ephemeral directory and verified **byte-for-byte
against their Git blob SHAs**, then run via the corrected Python unittest
discovery command: **10/10 passed**. Separately the canary YAML parsed and
its embedded Bash passed `bash -n`. Neither proves JVM compilation,
source-facing guest execution, physical Task retirement, AOT or source-org
runner capability.

## 2026-10-27: v2 runner label isolation and independent canary review

Standalone [draft source-free canary PR #2172](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2172) is restricted to a reviewed manual dispatch on protected `main` and has a **single changed workflow file**. Independent `@ORESoftware` review has been requested, not completed.

**DEPRECATED:** `oreslang-isolated-ephemeral` was used by historical PR-triggered workflows that remained queued. Never assign this label to a new runner. The reviewed source-free canary instead requires `oreslang-isolated-canary-2172-v2`. Provision a purpose-specific disposable worker with the **v2 label only**, plus built-in `self-hosted`, `linux`, `x64`, in a restrictive runner group for approved workflows. This provides label-level separation from the three legacy queued jobs, but it does not independently verify GitHub's runner-group restrictions, host credentials, or network isolation.

The Actions administrator still must cancel all old queued PR-controlled workflows (runs 40546884149, 40546920891, 40547417920) and verify no equivalent pending jobs, establish enforced `main` protection and independent review, then merge ONLY the approved canary workflow to the protected default branch. Do not register a runner or run any source-bearing job as a shortcut. The Java application PR must remain draft until actual JVM/AOT proof. Do not put the v2 label on any PR-triggered workflow.

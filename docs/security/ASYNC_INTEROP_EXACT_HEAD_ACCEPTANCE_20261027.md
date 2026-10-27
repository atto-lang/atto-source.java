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

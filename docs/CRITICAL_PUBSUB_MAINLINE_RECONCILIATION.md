# AttoLang Java mainline-first critical + pub/sub reconciliation

**2026-11-07 — DRAFT / source reconciliation only. Compiler, JUnit, Graal guest, and hosted CI: NOT_EXECUTED.**

- Source base: Java `main` `1c4ef1bafdcf403b189f72a34abb77872bd615d4` (Git tree `eaf7e102513aa71f93c1a443470b9d16358cf0a6`).
- Previous integration: draft PR #2645 head `9f3a7ba011cfb313bcd616c982a814b2f2421eef` (tree `87241d2c753309de3a1f101ff9bfd390cc6bc870`).
- Shared divergence base: `241bd67c39443cc2ef18df7e4ed456454af378c7` (tree `c12c206e232091f221e327a4cf1cd685b93a6b3f`).
- Mainline-first two-parent candidate: `f010388bd3a43e4c248fe71a4517b6adfe934575` (tree `6a69942733061fd3517bcf5acc9ff2a418881309`).
- Review: [draft PR #2647](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2647); original conflict: [#2646](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2646).
- 74 integration paths: 62 paths unchanged on main since the shared base were copied from prior integration **by exact blob**, and 12 dual-edited paths were reconciled manually using contextual source diffs. No upstream mainline-only path was replaced wholesale.

## Security and semantic conflict decisions

| Combined area | Mainline semantics retained | Integration semantics added |
| --- | --- | --- |
| ActorEventBus | Publisher-scoped subscription keys, per-observer topic quota, live-publisher checks, RELIABLE all-recipient credit preflight before sequence/reduction commit | Typed Topic facade, cross-topic subscription credits, constructor/race failure credit rollback, cancellation of owned pending publication admissions, sequence-bearing receipts |
| TypeChecker | Local-type-declaration fail-closed gate and dynamic select plan capability exclusions | CriticalEffectPreflight, actor-owned critical gate, fail-closed try-critical, Topic/Subscription/ActorGroup actor-boundary Send restrictions and suspends SourceTask boundary checks |
| AST/parser | Current named/local types and other mainline declaration semantics | CriticalStmt and TryCriticalStmt plus suspends admission and prohibited modifier combinations |
| Evaluator | Current mainline SourceTaskHandles and scheduler code | Critical gate execution, exact actor runtime admission, Topic/Subscription lowerings |
| ActorRuntime | Current generation, WAL proof, actor cell lifecycle and stop fences | Actor-incarnation-bound critical gate and pub/sub membership revocation |
| Ownership/test | Mainline capture and return paths, existing tests | Critical type-proof/task ownership epochs, non-Send negatives and actor critical regression fixtures |

Mainline ActorEventBus still uses `Math.addExact` for the **signed** sequence increment: it fails closed at signed overflow. This reconciliation deliberately **does not** claim full unsigned-u64 sequence-range parity. A dedicated overflow/wraparound guest test is required before extending it.

The integrated mixed canonical guest fixture is `contracts/v1/fixtures/critical-pubsub-integration-v1.{atto,ores}`, Git blob `9fe469af120fc948e1e68a55b783162c0b146105`, with **expected, not verified** stdout `subscribed|p-1|42`.

## New adversarial cases

`ActorPubSubAdversarialAdmissionTest`:
- `publisherScopedSubscriptionsShareTheCrossTopicActorQuota` — same-actor publisher-scoped leases count toward the global budget; repeated subscription is idempotent; closing a lease returns a credit for an unscoped topic
- `retiredPublisherRevokesItsScopedSubscriptionWithoutRevokingObserver` — generation/lifecycle exit closes scoped leases without terminating the observer

The exact-method Surefire verifier now requires these methods. The dedicated guest workflow also triggers on `integration/critical-pubsub-mainline-20261107`. These tests are authored only; until a real runner executes them they are **not passing evidence**.

## Release gates

1. Resolve [billing/runner admission #315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315), verify a source-free **nonzero-step** same-org canary, and then the exact-head GraalVM workflow.
2. Obtain Maven compilation, relevant JUnit/host adversarial suites, original pub/sub and critical negatives, mixed Polyglot Context.eval stdout and diagnostics at one verified Git head and tree.
3. Reconcile any regression revealed by tests; preserve RELIABLE atomic reservations, cancellation, generation fences, and critical effect/ownership safety.
4. Review #2647 independently and ensure main branch protection and any applicable private-source security holds are satisfied. Do not promote a draft to ready or merge based on source-only Git evidence.
5. Keep compiler-backed guest parity for LLVM, NativeAOT, Wasm and Forge **NOT_EXECUTED** until independently proven.

No cross-org source transfer, mirror, or protected-branch bypass is authorized by this document.


## 2026-11-07 — forward integration of current `main`

This section extends the earlier 12-file reconciliation above; it does **not**
replace or invalidate the original mainline-first conflict decisions.

- Old integration head: `dd0a1c84135c96c553c5397ec626905d614925e2` (tree `1231cc190029da2ee36a6c6c9e2419528b7fa7e8`).
- Advanced `main` parent: `7f356871211776c64e084b993fe6de66b02c4058` (tree `ab42d368b7558e9d0d4ff7e3eaac260c45f7bc1b`), **168 commits** past the first integration parent.
- Reconciled two-parent commit: `592ab1d2776b6f4d732fa7bb6fa194fc0f1d0ade` (tree `91f6088617bf553705dc55ee849ac705107f2ec2`).
- Of the 62 paths changed on advanced main, 59 are preserved by exact mainline blob and mode. The other 3 overlap the integration branch:
  - `ActorRuntime.java`: 17 current-main hunks; retain async-send pending-waiter quota, direct credit handoff, cancel-vs-seal barriers, actor-scoped critical tickets, generation/termination and pub/sub lifetime behavior.
  - `Parser.java`: 9 current-main hunks; retain fail-closed actor state/handle_state syntax, reserved root-module name, and `select over ... end` syntax alongside critical/suspends and typed Topic admissions.
  - `OresEvalRootNode.java`: 1 current-main hunk; reject synthetic root-module exports while retaining critical + Topic lowerings.
- **All 27 incoming hunks** matched unique exact contexts in the integration tree; replaying those hunks against the original main parent reproduced the advanced main source byte-for-byte.
- Git-tree read-back found 59/59 exact mainline blobs, 1,882 existing PR blob paths unchanged, and all 3 dual-edited blobs distinct from either source. The PR is zero commits behind advanced main as of this evidence.
- Mainline tests were not dropped. The combined exact-head script now **requires** named JUnit reports for `ActorAsyncSendAdmissionRaceTest`, `ActorAsyncSendBackpressureTest`, `SealedActorStateAdmissionTest`, `SelectOverEndSyntaxTest`, and `NamespaceModuleBoundaryTest`, in addition to the critical/pubsub and reject-before-commit suites.
- The previously authored `failedReliableAdmissionCannotCommitReductionOrConsumeSequence` test is still required by the pub/sub gate. It rejects a full RELIABLE publication before reduction commit, sequence increment or new fanout.
- **Evidence class: source-tree only**. No locally executed compilation, Maven/JUnit, GraalVM guest run, or hosted exact-head test has passed. New source-org workflow runs continue to fail before runner admission (`runner_id=null`, `steps=null`). In particular, `subscribed|p-1|42` remains **expected stdout, not observed stdout**.
- Do not promote the draft, merge into main, accept skipped/zero-step CI, bypass billing/security controls, or mirror private source without independently authorized review and transfer controls.

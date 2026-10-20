# Scalar Task admission and execution audit — 2026-10-19

**State:** DRAFT security/semantic integration hardening on Java PR #1484,
stacked on #1479 → #1475 → #1466 → #1411. Do not merge or remove the
broader Task execution gates without exact-head executable validation.

## Canonical syntax and normal form

- `task { ... }` is the preferred Task-producing expression.
- `do task { ... }` is the preferred explicit detached statement (currently
  compile-rejected by general execution policy).
- Legacy `nb { ... }`, `nb task { ... }` and `do nb { ... }` remain parser
  compatibility aliases; they construct exactly the same `Ast.TaskExpr` /
  `Ast.TaskBlockStmt` and **do not** introduce a second scheduling tier.
- Nonblocking `nb readch`, `nb writech`, `nb select` and `nb cb writech`
  retain their separate meaning.
- Only direct `await task { return <literal-or-local-scalar>; }` is currently
  eligible for guest execution in this draft. No general `Task<T>` binding,
  nested/escaping handle, detached body or user callback is enabled.

## Threat model / authorization chain

| Stage | Verified-by-code boundary | Still forbidden |
|---|---|---|
| Parser | Canonical and legacy spellings normalize to one AST | Bare discarded Task expression |
| Ownership checker | Capture traversal and suspension borrow constraints | Borrowed / move-only / actor-private Task values |
| Type checker | Trusted public, ordinary, zero-argument root `fnc main`, direct await, scalar return and a lexical local name only | Foreign/module-global names, escaped Task handles, nested callbacks, other functions, async/generator/trapped/nlex entrypoints |
| Source continuation | `ScalarTaskPlan` enforces single return expression; parent evaluates captured scalar once | Guest calls, FFI, arbitrary AST or Env transferred to worker |
| Entrypoint provenance | The VM `executeMain` mints a per-dispatch launch mark; only the matching `SourceTask` retains an immutable entrypoint proof | Merely being named `main`, being called via exports, or using root scheduler |
| Runtime scalar frame | Exact `Long`, `Double`, `Boolean`, `String` class whitelist; independent snapshot; maximum 16 captures and 1,048,576 UTF-16 units per string | Native handles, references, actor objects, arrays, null, nested Task/Future, unbounded string copies |
| Scheduler | Existing `OresScheduler.Await` resumes heap-owned continuation and does not park a physical carrier | Blocking guest `AsyncRuntime.await`, arbitrary Task closures |
| Lifetime | Context-owned scope retains child until physical completion; terminal worker failure releases through `getDone()` | Claiming cancellation itself is physical quiescence |

## Corrected integration weaknesses

1. Name-only or resolved scalar type was previously sufficient to obtain the
   scalar Task capture shortcut. Now the compiler requires the binding to
   originate from the lexical root main (never its module/global parent).
2. The root-main flag could leak into syntactically nested/deferred analysis.
   Normal lambdas, expected-type lambdas, mutex callbacks, isolated callback
   blocks, select arms and class method analyses explicitly clear it and
   restore their previous state on all exits.
3. Root scheduler identity alone was not sufficient to establish root
   entrypoint provenance. The runtime now requires an immutable launch proof
   captured by a SourceTask built during actual `executeMain`; exported
   calls to a function named `main` cannot forge this fact.
4. The static and runtime entrypoint checks agree on exact public ordinary
   `fnc main` requirements, and reject untrusted, actor, async, generator,
   trapped, nonlexical and generic entrypoints for the initial slice.
5. String transfer is limited before scheduling. Regression tests check both
   string size boundaries and legacy syntax spellings.
6. Runtime documentation no longer implies root cancellation physically joins
   or automatically cancels its independent child. Context teardown does
   retain and drain the scalar frame.

## Verification status

**Actually executed (2026-10-19):** Exact GitHub production blobs
`ScalarTaskPlan.java` (`80fe383cc95d4ce7a154c7af55e6d9546e8c8dac`)
and `OresScalarTaskFrame.java`
(`3d11cd5ddb3c85115b73e1cfe251810a604c3751`) were copied to an isolated
Java 21 probe and **independently verified with `git hash-object`**.
Both compiled with `javac --release 21 -Xlint:all -Werror` against
minimal test-only AST/scheduler stubs. The shape validator passed 10/10
assertions; scalar frame passed 13/13 assertions (copy semantics, checked
addition, overflow/replay, mutable/native object denial, null and index
bounds, 16-capture cap and string-size limit). These are **source-file-only
smoke tests**, not the real scheduler, compiler, parser, or Graal guest runtime.

The four upstream #1466 files at `78cbe9297f63705b2c728caf94478f3d171a7cc6`
(`TaskAccessVerifier`, `ScopedTaskTypeBridgeTest`,
`TaskAccessVerifierTest`, `TaskRegionHostScopeIntegrationTest`) were
synchronized to the #1484 branch with verified **identical upstream Git
blob SHA**. This is **content-level reconciliation** only; the descendant
Git ancestry still needs semantic review/rebasing before merging.

**Not executed/verified:** Maven/JUnit, complete TypeChecker/OwnershipChecker
compilation, Graal polyglot `.ores` guest execution, scheduler cancellation/
failure stress on the exact PR SHA, native image, LLVM, and ECS/region
permission lowering. A passing syntax/static check is not a full guest pass.
The source organization's Actions jobs are queued or blocked, not green.

Suggested first runnable exact-head source-repo gate:

```sh
mvn -B -ntp -Dores.runtime.carriers=java \
    -Dsurefire.failIfNoSpecifiedTests=true \
    -Dtest=TaskSpellingSyntaxTest,TaskBlockSyntaxTest,GuestScalarTaskAwaitTest,TaskSpawnSendAdmissionTest,TaskTraitAdmissionHardeningTest,OresScalarTaskFrameTest,OresTaskScopeFrameTest,OresTaskPartitionQuiescenceTest,ScopedTaskTypeBridgeTest \
    test
```

Then run the complete Java CI, security/concurrency stress and GraalVM/native
matrix. Only authorized private-source runners may execute those tests; do
not mirror private repository contents to another organization without
independent security authorization.

## Remaining release blockers

- Parent-to-child cancellation linkage and scoped child ownership for all
  nontrivial Tasks. A context-owned pure scalar child may outlive its canceled
  root temporarily but must be joined before VM teardown; do not generalize
  that arrangement to mutable data, arenas or FFI.
- Authentication of canonical runtime intrinsic identities for future
  `Channel`, `Arc`, `RwLock`, `Task` and other Send/Sync capabilities.
  Nominal names alone cannot grant these traits.
- Compiler-minted allocation-domain generation/ownership permits and
  checked runtime pin acquisition/release on **physical** task exit, not
  cancellation/result settlement; guest `InvertedArray`/ECS parallel writers
  remain disabled.
- Java/Graal full guest AOT and native LLVM zero-GC AOT semantics parity.

**No merge until executable checks run and blockers for the enabled slice
are resolved.**


## CI infrastructure evidence (October 19 follow-up)

Run [fast-ci 39293938642](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39293938642)
failed with zero steps (job 118201467279). Run
[concurrency-contract 39293938694](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39293938694)
failed with zero steps (job 118201465615). This is neither a real compiler
failure nor a test pass; actual runner-allocation root cause remains unverified.
Evidence was added to existing [CI blocker #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963).
Cross-org transfer remains prohibited by [#962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962).

## October 19 continued hardening and private-CI gate

- The source-repository default-branch `.github/private-ci-approved-repositories.txt` has **zero approved destinations**. The private-source mirror procedure remains blocked by open security [#962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962); availability of another GitHub organization's Actions minutes does not authorize source copying. No private Git tree was transferred.
- The GitHub integration cannot read Actions billing/minutes/budgets, so runner failure cannot be attributed to exhausted minutes. Latest inspected parent [#1475 fast-ci run 39303489132](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39303489132) and [concurrency-contract run 39303489061](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39303489061) failed with **zero job steps**; see [CI issue #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963).
- Discovered upstream `TaskRegionPhysicalExitBridgeTest` in the `runtime` package imported package-private `TaskAccessVerifier` from `types`. Fixed without widening production visibility: a **test-only** `TaskRegionTestLedger` fixture lives inside `types` and exposes narrowly limited observations and physical-exit hooks. The runtime test uses that fixture instead of reaching into the compiler ledger. Applied to #1475, #1479 and #1484.
- Added failure-path coverage: a throwing child must not release its compiler loan before its `onPhysicalExit` callback; a sibling must also quiesce before parent access resumes.
- Synchronized latest #1475 region verifier tests into #1479 and the physical-exit bridge into #1484; updated both Maven selector lists to retain the new tests while preserving scalar-source tests. These changes are **file/content reconciliation**, not Git ancestry rebases. GitHub still reports #1479 and #1484 as unmergeable against advanced draft ancestors. Do not merge or force-update without resolving their ancestry semantically.
- Closed an additional type-checker authority leak: `defer` expressions no longer inherit root-main-only scalar Task admission; `GuestScalarTaskAwaitTest` now has a parser-valid negative fixture.
- **Locally executed on Java 21:** exact committed `OresScalarTaskFrame.java` blob `3d11cd5ddb3c85115b73e1cfe251810a604c3751` compiled with `--release 21 -Xlint:all -Werror` and passed 15/15 isolated checks using a minimal stub scheduler. Exact test-only `TaskRegionTestLedger.java` blob `39a5c80ae46984e0e05dd995dc2bf87fc0985082` compiled with the same flags and passed 10/10 isolated fixture checks against a stub verifier. **Neither is actual Maven/JUnit, real verifier integration, actual scheduler execution or guest GraalVM validation.**

Required before enabling a cross-org mirror: independently reviewed protected-source immutable ref/tree, current private source/destination numeric identity and active visibility, a source-and-destination preflight wired into the actual publisher, trusted pinned approval policy and exact approved destination ID, plus owner/security approval. At present there is **no approval**. Prefer an authorized private runner on the source repository and a real exact-head Maven/JUnit job.

## October 19 PR ancestry reconciliation and root-launch hardening

- PR [#1479](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1479) now contains a verified two-parent semantic merge `8375b795cde1c34ba8242623742e40835ec73618`, including parent #1475 `358c09b7b0c639a538995feca8e20b6e95319211`. The resulting Git tree was **identical** to the previously reviewed #1479 tree. All parent-modified files matched exact blobs except the CI workflow, whose selected test set is a strict superset of the parent's.
- PR [#1484](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1484) similarly contains two-parent merge `9569a09f7700a430386fc1568de1e7b155610385` with #1479 `8375b795...` as its second parent. Its Git tree was identical to the reviewed #1484 tree; the only inherited-file difference is a superset CI selector. Both comparisons are **zero commits behind**, both GitHub PRs now report `mergeable=true`, and both remain **draft** with `mergeable_state=unstable` due unpassed checks.
- **Additional root-launch provenance hardening:** `OresEvalRootNode.SourceTask` now consumes the VM entrypoint's thread-local grant on its first matching construction. A nested/reentrant source function named `main` cannot replay the grant before the original `executeMain` returns. Added `GuestScalarTaskAwaitTest.directExportedMainInvocationCannotReuseRootLaunchProof`, exercising successful genuine root launch followed by a rejected `INVOKE_PUBLIC_COMMAND` entrypoint call. This JUnit test is **committed, not executed**.
- Latest #1484 HEAD after the extra test: `5bfacab43fd767279bd29933a810c4249ebad0fd`. Source-org [fast-ci run 39307637199](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39307637199) and [concurrency-contract run 39307637192](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39307637192) both concluded failure with **zero executed steps**, not Maven failure evidence.
- Re-ran local Java 21 isolated exact-source probes: `OresScalarTaskFrame.java` 15/15, `TaskRegionTestLedger.java` 10/10, `javac --release 21 -Xlint:all -Werror`. Production-source Git blob hashes were reconfirmed. These smoke checks use stubs and **do not** validate the new VM launch-grant change or full-source guest execution.

**Next required gate:** Restore an approved source-local runner; execute the full `test-compile` and targeted JUnit selection, including both guest root-launch and physical-exit tests; then run all CI suites. No cross-org private source transfer is authorized by the current empty default-branch allowlist and unresolved security issue #962.

## October 19 deferred-Future cancellation audit and CI restriction

Latest audit of the evolving Task scope (including pre-admission ownership,
cancellation ordering and physical-exit tests):

- **Corrected an irreversible cancellation allocation window** in
  `OresFuture.cancelDeferredNotification()`. Previously the
  `AtomicBoolean` single-delivery guard and notification closure were created
  **after** `PENDING -> Cancelled` committed. Allocation failure at that
  point could leave the cancellation revocation barrier closed permanently,
  with late/early runtime subscribers unnotified. The guard and closure now
  allocate **before** the terminal CAS; the losing CAS discards them without
  effects. Production file blob after change:
  `28f6e8bd2ab6f77a3fa2e3c1e2c8f10bfc48e594`.
- **Added regression** `OresTaskScopeCancellationOrderingTest.
  deferredFutureCancellationRevokesBeforeDeliveringOldAndLateWaiters`:
  cancellation hooks run before either early or late subscriber,
  notifications wait for explicit delivery, and repeated delivery is inert.
  The Java/JUnit test is committed but **not executed**.
- **Source invariant check executed** against current GitHub blobs: the
  notification guard/closure precede the state CAS and no guard allocation
  remains after the irreversible CAS. This is a static inspection, **not**
  a compiled-source test of `OresFuture`.
- **Local Java 21 tests executed:** Git-blob-matching production scalar frame
  `3d11cd5ddb3c85115b73e1cfe251810a604c3751`, `15/15` isolated
  smoke checks and `100 × 12` competing virtual-thread resumes, one winner
  per round; test-only region ledger facade
  `39a5c80ae46984e0e05dd995dc2bf87fc0985082`, `10/10` isolated
  checks. `javac --release 21 -Xlint:all -Werror`. These tests use
  minimal scheduler/verifier stubs and **do not** validate the latest
  `OresFuture` or full Maven/GraalVM guest runtime.
- **Current head before this documentation update:**
  `e8fca59971296dfabe51d32eb2829d514c35b098`.
  [fast-ci 39313270175](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39313270175)
  and [concurrency-contract 39313270412](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39313270412)
  failed with **zero steps**. No evidence of a Java/Maven/JUnit failure
  or of exhausted Actions minutes follows from a zero-step job.
- **Cross-org private mirror remains blocked:** the default-branch
  `.github/private-ci-approved-repositories.txt` has zero uncommented
  destinations, [security issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962)
  is unresolved, and the connector provides no org billing/minute balance.
  Even a private org with available Actions minutes cannot receive private
  source until explicitly approved under the source-provenance/destination
  security gate. No code was transferred.
- **Still required:** approved exact-head source-local runner, JDK21/25
  Maven `test-compile`, targeted `concurrency-contract`, full Java suite
  and GraalVM guest positive/negative execution, then LLVM parity.

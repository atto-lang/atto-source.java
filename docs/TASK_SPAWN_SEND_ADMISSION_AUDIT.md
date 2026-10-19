# Task spawn Send admission — implementation/audit slice

Status: **draft compiler ownership pass; guest Task execution disabled** (2026-10-18).

This branch stacks on the Java Task syntax/ownership parser PR #1377, not main.
It carries an AST-level `SendSyncTraits` **deny-only syntax preflight** derived
from #1447. It may reject obviously unsafe captures before affine moves, but a
positive result is explicitly **not a Send permission**. The authoritative
decision must later be made by `ThreadSafetyTraits` over resolved
`Types.Type`, including owned-transfer provenance/lifetime checks. The public
AST helper API is therefore named `mayBeSendAfterResolution` /
`mayBeSyncAfterResolution` rather than `isSend` / `isSync`.

## Implemented source-phase admission

- An `nb { ... }`, `task { ... }`, `do nb`, or `do task` captures outer
  values as an independent escaping activation. Before `checkLambda` can
  consume any affine value, `OwnershipChecker.validateScheduledCaptures`
  collects transitive capture names and runs the conservative AST Send preflight.
  Passing that preflight does not authorize scheduling.
- Immediately invoked `async task |...|` and `async |...|` reject unproved
  non-Send closure captures, scheduled parameters and owned arguments.
  The original lexical borrow, actor-state, mutex-guard, constructor, move,
  cancellation and suspension restrictions remain active.
- `Array<T>` / `List<T>` are explicitly treated as **owned moves only**
  when T is recursively Send. Neither receives Sync, actor transport or
  shared-domain authority. Built-in `Option<T>` / `Result<T,E>` are permitted
  only when every payload type is recursively Send. User-shadowed types with
  those names cannot impersonate the compiler intrinsic.
  Future runtime ABI changes must re-audit these compiler-specific
  trusted-intrinsic decisions before source execution.
- The AST shape walker is bounded at 64 levels / 4,096 nodes before recursive
  derivation, so deep or very wide unresolved capture types fail closed without
  poisoning subsequent checks.
- No automatic admission for `Arc`, `Shared`, `Mutex`, `RwLock`,
  `InvertedArray`, `Future`, `Task`, `Channel`, `ActorRef`,
  `ExecutionPermit`, `ThreadBound`, native pointers or unknown nominal
  types. Even an apparently harmless guest class named `ActorRef` cannot
  spoof a built-in capability whose Send proof is not implemented.
- A shared borrow is not an owned Send capture. Scoped child borrows are
  **not** yet enabled: they require verified canonical region identity,
  TaskScope scope/outlives, quiescent Task completion and structural fencing.

## Testing / limitations

`src/test/java/dev/oreslang/TaskSpawnSendAdmissionTest.java` adds
real Parser + OwnershipChecker regression cases for:

- actor-free scalar and owned-array captures;
- use-after-move denial for an owned captured array;
- raw pointers, native/FFI handles, ActorRef and transitive Option/Array
  capability rejection;
- owned `Option<int>`/`Result<int,string>` positive captures;
- immediate async/task lambda parameter/capture safety;
- deep/wide AST type-bomb rejection with a healthy post-budget query;
- preservation of the `OresCompiler.parseAndTypeCheck` failure for unlowered
  `nb` Task execution.

**The new JUnit suite is committed but not proven to pass under a full
Maven/GraalVM build.** The existing source-organization GitHub Actions
infrastructure has recently reported zero executed job steps. Do not
relabel syntax/ownership acceptance as actual runtime scheduling.

## Integration gates (not implemented)

1. Complete removal of AST `SendSyncTraits` as a positive permission engine.
   It is now deny-only preflight on this branch. After type resolution, every
   Task capture/parameter/argument must call canonical `ThreadSafetyTraits`
   (including `isOwnedSend`/transfer provenance). A conversion of an
   unresolved AST nominal to a resolved Type must never upgrade rejected syntax
   to permission, and AST acceptance must never bypass the resolved authority.
2. Wire real Task AST captures, typed `Task<T>`/await and permitted
   region loans to #1448's `TaskAccessVerifier`; mint root ownership
   provenance only from verified compiler allocation/alias identity.
3. Integrate #1407's tracked TaskScope and `getDone` non-cancellable
   physical-quiescence barrier; cancellation requests do not drop loans.
4. Connect typed `InvertedArray` and ECS columns to LLVM #661's
   hardened range borrowing, without creating an incompatible duplicate
   storage layout.
5. Run exact-source JDK25/GraalVM JUnit, LLVM native/guest positive and
   compile-negative source tests, full CI, ASan/UBSan/TSan, actor-domain
   isolation cases and benchmark allocation/cache effects before promotion.

Cross references:
- Java #1377 https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1377
- Java #1439 https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1439
- Java #1447 https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1447
- Java #1448 https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1448
- LLVM #661 https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/661

## Cross-branch exact implementation checkpoint

The Task branch originated from #1447's bounded AST trait walker, but its
current role has been deliberately narrowed to deny-only preflight. The latest
copy also restores #1447's 64-depth / 4,096-node hostile-shape bound. Current
`SendSyncTraits.java` blob is `6dcdb577037d6c1e38cbc02ad3c1b92ec9b1ae22`;
it is **not** an authoritative semantic trait engine and must not be compared as
permanent parity with #1439/#1467.
The canonical PR may advance independently; recheck its latest head before
merging, rather than assuming permanent branch parity. #1447 now includes an
additional test showing that source classes named `ActorRef`, `Task`,
`Channel`, or `ThreadBound` cannot manufacture a Send or Sync proof.
This is code alignment, not proof that the two PRs can yet be merged without
reviewing their distinct base branches and compiler type representations.


## Resolved semantic authority bridge — 2026-10-19

The Task branch now has a real second-stage semantic gate in `TypeChecker`,
rather than relying only on the AST preflight:

`TaskCaptureAnalysis -> canonical Types.Type -> ThreadSafetyTraits.isOwnedSend -> isSharedSafe`

- `TaskCaptureAnalysis` is shared by `OwnershipChecker` and `TypeChecker`, so
  the affine move pass and semantic permission pass consume the same free-variable
  set.
- Expression-form `match` is now traversed. Previously, a value referenced only
  from a value-match arm could be omitted from the private ownership capture
  scanner.
- Task-block captures, Task/async-lambda captures, declared scheduled parameters,
  and immediately-invoked scheduled arguments are checked using resolved
  `Types.Type` before the existing execution-disabled diagnostic.
- A positive semantic Send result still does not enable Task execution.
- Recursive nominal storage now fails closed instead of using the old
  `if (!seen.add(klass)) return true` positive fixed point.
- Shared-safety traversal is bounded to depth 256 / 100,000 proof steps and
  rejects readonly record views as shared-safe backing.
- `OptionUnwrapError` is source-unnameable and remains compiler-internal.

Current exact blobs:
- `TaskCaptureAnalysis.java`: `88da2bd75bd52decdb5ba9f22e914d0e6cfd75ec`
- `ThreadSafetyTraits.java`: `c1340d77a38b2a0ad5236e1d1a894ccfe1adaba5`
- `TypeChecker.java`: `eb0099240519261cae1d083e711b7ccdf8f25a7e`
- `OwnershipChecker.java`: `dcf3e9c41c3d85886ed957a6035ad713af3a8de3`
- `TaskSpawnSendAdmissionTest.java`: `c07d7159ff44cac63a2f6223d3395847a885245d`
- `ResolvedTaskSendAuthorityTest.java`: `08dd36257672de0398a7cbae3b3f84ceb85d7ab9`
- `TaskCaptureAnalysisTest.java`: `5df21d3a9b12cac6403fc695aa8de3ba5d10a043`

Committed targeted test methods in these three suites: **23** (13 + 5 + 5).

### Proof-work amplification hardening — 2026-10-19

The resolved Task bridge originally called the bounded `isSharedSafe` walker
from the canonical `ThreadSafetyTraits` nominal callback using a fresh
100,000-step budget for every distinct nominal node. A sufficiently wide type
could therefore multiply the intended compiler-work bound even though each
individual traversal was bounded.

`isThreadSend` now allocates one query-local 100,000-step nominal
shared-safety budget and passes that same budget through every nominal callback.
`ResolvedTaskSendAuthorityTest` adds a wide nominal-storage adversarial case:
the direct shared-safety traversal and owned-Send query must both fail closed
at the same global budget, and a subsequent primitive query must remain healthy.

Current exact blobs:
- `TypeChecker.java`: `e870b9d27c03e5377116fb2c27cf5f9371e6acee`
- `ResolvedTaskSendAuthorityTest.java`: `30e0926f957e1e699ca677e2c069997511d22f6d`

Current exact branch head: `3dd8590fdd9537aa6b34fcc8e61390991e115c29`.
Latest source-org fast-ci #39318279186 / job #118285359818 and
concurrency-contract #39318279228 / job #118285360290 again completed with
**zero recorded steps**. They are infrastructure failures, not compiler/test
results. No Maven/JUnit/GraalVM pass is claimed for this head.


## Compiler-minted transfer provenance — 2026-10-19

The ownership pass now produces a sealed, identity-bound
`TaskTransferProvenance` ledger in addition to ordinary accept/reject
diagnostics.

This is deliberately an **ownership/provenance half-proof only**:
`ThreadSafetyTraits` remains the sole resolved semantic Send/Sync authority.

New invariants:

- provenance is keyed by the exact checked Task AST node using identity, so an
  equal-looking/reconstructed node cannot claim an existing proof;
- a capture receives `UNIQUE_MOVE` only after the affine owner is actually
  marked moved and has no live immutable or mutable borrow;
- copy captures receive `COPY` and leave the original owner usable;
- fresh/returned consuming arguments may receive `OWNED_RVALUE`;
- a move-only member/index projection is rejected for scheduled arguments
  until projection-level move tracking exists; callers must first move the
  value into a named local;
- that rejection follows ownership-bearing wrappers (including `rt take`,
  casts, aggregates, calls' arguments, conditional/match values) so an
  untracked projection cannot be laundered into `OWNED_RVALUE`;
- callable selection itself is not treated as field extraction: a safe fresh
  move-only result such as `optional.unwrap()` remains eligible for ordinary
  ownership/provenance checking;
- the ledger is sealed before it leaves `OwnershipChecker`;
- both ordinary and explicitly linked-class ownership entrypoints can return
  the same sealed provenance result;
- no provenance result is returned when ownership checking fails.

New exact files/blobs at this audit point:

- `OwnershipChecker.java`: `c72d42845b688b498ed83c0a9ec913c25bab3fe5`
- `TaskTransferProvenance.java`: `4f9e8a9b346eb2835ea3f05ae55bcda05c983d76`
- `TaskTransferProvenanceTest.java`: `736a7b172c70337fc34c36e542a60541b60484df` (5 JUnit methods)
- `TaskTransferProvenanceSmoke.java`: `8294f90a653bbb71ad0ba87fa57636ef1e499d23`
- dependency-free Task smoke script: `87730f7739e2bffb5f8271d69cbc82f716fbd756`

The targeted Task branch now has **28 committed JUnit methods** across
Task-spawn admission (13), resolved semantic authority (5), shared capture
analysis (5), and transfer provenance (5).

The ownership proof is not yet sufficient to enable source Tasks. The next
front-end integration must return, for the same exact AST boundary, both:

1. resolved semantic eligibility from `ThreadSafetyTraits.isOwnedSend`; and
2. the sealed `TaskTransferProvenance` ownership proof.

A lowering-specific front-end result should combine those without inventing a
second type resolver. It must then bind the admitted lifetime to #1407's
physical `TaskScope.getDone()`/descendant-quiescence barrier. Cancellation
request or result-Future settlement alone is never a release witness.

Latest audited branch head: `3717ac52717ca2eb7d153b47a79e5a3ab621624a`.
Source-org fast-ci #39324361246 / job #118306236723 and
concurrency-contract #39324362365 / job #118306241700 again failed with
**zero recorded steps**. No Maven/JUnit/GraalVM pass is claimed. The
authorized cross-org exact-tree path remains blocked until #1430 is
independently approved and merged into protected `main`.


### Opaque-import and projection-selection hardening

A further audit closed two admission edges:

1. **Opaque imported runtime captures fail closed.** A Task capture that is not
   a local `Env` binding but is present in the TypeChecker's runtime-value
   import namespace can no longer be silently skipped. Detached blocks and
   scheduled lambdas reject it until linked semantic type **and** ownership
   metadata are available. Compile-time namespaces remain distinct from this
   rule.
2. **Callable member selection is not a moved field.** The recursive
   projection-laundering guard follows ownership-bearing call arguments but
   does not treat the `MemberExpr` used to select a method as an extracted
   affine field. A fresh move-only return such as `optional.unwrap()` can
   therefore be admitted normally, while direct `nested[0]` and wrapped
   `rt take nested[0]` scheduled transfers still fail closed.

Current exact head before this documentation commit:
`71feabe479ebc3b3d5b3510fd0eaac3500eecd32`.

Current exact compiler/test blobs:
- `TypeChecker.java`: `1e3e40d96b36f37d6379d382dfafc3d3a565c2fa`
- `OwnershipChecker.java`: `c72d42845b688b498ed83c0a9ec913c25bab3fe5`
- `TaskTransferProvenance.java`: `4f9e8a9b346eb2835ea3f05ae55bcda05c983d76`
- `ResolvedTaskSendAuthorityTest.java`: `c863c079f3324d214031dc8ca8e3e4ba94386755` (7 tests)
- `TaskTransferProvenanceTest.java`: `736a7b172c70337fc34c36e542a60541b60484df` (5 tests)

Targeted Task admission inventory is now **30 JUnit methods**
(13 spawn/preflight + 7 resolved semantic authority + 5 capture analysis +
5 transfer provenance).

Latest source-org fast-ci #39324842529 / job #118307863719 and
concurrency-contract #39324842366 / job #118307861781 both completed with
**zero recorded steps**. They are runner/admission failures, not compiler or
JUnit results. No exact-source pass is claimed.

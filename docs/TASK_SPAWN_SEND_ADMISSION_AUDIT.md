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
current role has been deliberately narrowed to deny-only preflight. Current
`SendSyncTraits.java` blob is **not** an authoritative semantic trait engine
and must not be compared as permanent parity with #1439/#1467.
The canonical PR may advance independently; recheck its latest head before
merging, rather than assuming permanent branch parity. #1447 now includes an
additional test showing that source classes named `ActorRef`, `Task`,
`Channel`, or `ThreadBound` cannot manufacture a Send or Sync proof.
This is code alignment, not proof that the two PRs can yet be merged without
reviewing their distinct base branches and compiler type representations.

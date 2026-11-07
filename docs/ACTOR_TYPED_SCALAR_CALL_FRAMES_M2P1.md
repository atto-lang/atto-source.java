# AttoLang actor-owned typed scalar invocation slots — Java M2.1

**Status:** authored, not yet hosted-executed or compiler-parity-certified.
Stacked on [Java #2628](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2628)
→ [#2627](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2627)
→ #2624 → #2505. This bridge is a *limited real-data* lowering, **not**
a stackless interpreter, independently allocated native stack, or native
process stack-overflow containment guarantee.

## Exactly what changes

- `OresEvalRootNode.Evaluator.invoke` now uses
  `GuestInvocationFrame.enterWithArguments` and
  `restoreScalarArguments`. For a list consisting only of supported scalar
  primitive wrappers, the actual arguments are encoded into the current
  actor-incarnation-owned frame and decoded back out *before* `executeRaw`
  and source parameter binding. This is more than a metadata-only model.
- `GuestCallDepthGuard.enter(frameBytes)` reserves **one** managed actor
  frame sized to `max(32, 16 + 9 * argumentCount)`; no second byte charge or
  frame-depth decrement. The first 16 bytes hold existing call-kind, arity
  and tail-hop metadata. Every argument takes one signed type tag plus eight
  big-endian data bytes. Actor arena quotas apply before the next native
  interpreter invocation.
- Exact types: Java Byte, Short, Integer, Long, Float, Double, Boolean,
  Character. Float/double raw IEEE bits are retained, including NaN payloads.
  No implicit type conversion or host object identity is transferred.
- Null, BigInteger, Strings, collections, closures, objects, capabilities,
  arbitrary reference roots, and more than 256 arguments are **not copied**
  to the arena. Those calls transparently retain their existing JVM
  evaluator path. The unsupported values are never encoded as raw pointers.
- A tail call can reuse the current frame for any compatible scalar layout.
  Tail calls to wider arities or unsupported values remain valid but fall
  back to JVM argument values instead of enlarging an active frame or
  pretending data was lowered. The old primitive payload is scrubbed on
  transfer; there is no second frame or native recursion added by the
  descriptor update.
- Frame ownership still uses the parent `ExecutionContext` call-chain ID
  and actor generation, and still follows exact carrier depth and LIFO
  cleanup. A malformed arity/type-frame read is terminal to the actor arena
  even if a host callback catches the exception. Foreign-carrier access is
  denied without letting that carrier mutate the owning actor.
- On non-actor calls, the same carrier guard continues; there is no
  actor-owned arena or invented actor identity.

## Verification inventory (NOT_EXECUTED on hosted exact head)

- 7 JUnit host/runtime methods in `GuestScalarCallFrameTest`:
  exact primitive tags/roundtrip with NaN payloads; malformed tags,
  noncanonical payload and object rejection; actor-frame typed roundtrip;
  widening tail fallback/reference exclusion; foreign-carrier denial;
  actor-byte exhaustion before 64 guest native calls and sibling progress;
  malformed arity catch-and-continue denial.
- 1 JUnit compiler/evaluator method in
  `GuestScalarActorInvocationLanguageTest`, compiling/running a
  **legacy `.ores`** function taking four `int` arguments inside an actor,
  asserting output `10` and a frame-byte high-water mark.
  This is **authored only**; actual compiler-backed execution remains
  `NOT_EXECUTED` until stepful Maven/JUnit proves it.
- Parent #2628's 5 host descriptor tests + 3 canonical `.atto` fixture
  tests and parent #2627's tests remain separate. Do not sum authored
  assertions as if they were executed receipts.

Required exact-head commands, executed on a real admitted Java 21+/Graal
runner with the pinned head commit AND Git tree:

```sh
mvn -B -ntp -Dtest=GuestScalarCallFrameTest,GuestScalarActorInvocationLanguageTest,GuestInvocationFrameTest,ActorGuestInvocationLanguageTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test
```

Capture JUnit XML, executed job steps (nonzero), source/fixture blob IDs,
logs, compiler + runtime version and negative mutation checks. Existing
`steps=null`/empty are **runner-admission failures**, not compiler regressions.

## P0 remaining for fault containment

This actor-owned storage currently contains **scalar call inputs only**.
Guest local bindings, operands, reference roots, return PCs, nested
expression recursion and suspended `SourceTask` state remain on JVM
carrier-stack / heap objects. A complete implementation requires typed
compiler frame layouts and an iterative, stackless instruction dispatcher
for every guest path, plus scheduler-owned logical task leases surviving
await/carrier migration. The hard acceptance is a real compiled canonical
`.atto` actor A stack-budget failure, sibling B survival on a reused
worker, typed supervisor CONTROL failure and fresh-generation restart,
defer/critical cleanup, stale continuation rejection, and process survival
under low native stack and hostile callback tests.

Until those receipts exist, no Java/GraalVM, LLVM, .NET, Wasm or Forge
backend may claim physical actor-stack parity. Never catch JVM
`StackOverflowError` or native stack faults on a shared carrier.

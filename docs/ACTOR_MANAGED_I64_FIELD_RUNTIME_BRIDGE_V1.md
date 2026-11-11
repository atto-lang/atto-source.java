# AttoLang managed actor `i64` state — first physical field bridge V1

**Status: Java/Graal runtime and source evaluator DRAFT. The 100 focused tests
are authored, not executed. This is a narrow experimental first slice,
not general source-level actor/supervisor shared-memory support.**

Stack:
[Java #2848](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2848)
→ this new source/guest field-storage slice. Its predecessors include
#2841, #2831, #2825, #2820, #2816, #2758 and #2296.

## Security problem addressed

Earlier `ActorStateSupervisorLoans.protectedScalar` was a dedicated
host proof slot. Actual source actors stored fields separately inside
`OresEvalRootNode.SourceActorState.object.fields`. Passing one scalar
loan could never justify accessing the real object map. A shadow or
copied field would leave two writable representations, defeating
physical reader/writer exclusion.

This slice adds **named `ActorStateSupervisorLoans.LongField` cells**
owned by exactly one runtime-minted region. Each nominal field handle
has no standalone read/write API. The region requires an authenticated
owner physical turn or a supervisor CONTROL physical loan for all
value accesses:

- `declareOwnerLongField(name, initial)`: exclusive owner WRITE loan,
  bounded named identity (64 fields), duplicate rejection, no unguarded
  metadata mutation, and failure rather than parking an actor carrier.
- `readOwnerLongField(field)`: lexical owner READ, primitive value.
- `updateOwnerLongField(field, operator)`: exclusive owner WRITE;
  user computation happens outside the region monitor while physical
  admission remains held, then STOP-checked commit under the monitor.
- `ScopedScalar.read(field)` / `write(field, value)`: live
  authenticated one-turn supervisor CONTROL, exact same region/handle
  identity, a reader cannot write, view invalidated before finally
  releases the physical loan.
- Any foreign field, stale loan, revoked generation or STOP-sealed
  region denies access. The field handle is not a raw pointer or a
  mutable Java object reference.

The previous standalone `protectedScalar` host fixture remains for
existing tests but is **not used as the source i64 field backing**.

## Narrow actual source evaluator integration

`OresEvalRootNode.Evaluator.initializeActor` now recognizes fixed-width
`i64` declarations in actor fields. Only when all of these are true:

1. The actor was spawned as a **structured supervisor-managed code
   worker** using the new `ActorGroup.spawnCodeWorker(...)` path.
2. Its source methods are declared synchronous/non-suspending, have
   no source loops and no `tear_down`.
3. Each declared `i64` slot was initialized before actor READY and
   its value is an **exactly representable signed 64-bit integer**.
4. The actor is **not UNTRUSTED**; structured untrusted tenants remain
   isolate-only and receive no process-shared primitive field region.

...the code executor obtains a region via the restricted
`ActorContext.createManagedPrimitiveStateRegion` initialization hook,
mints an `i64` cell per source field, **removes the original field
value from the guest's ordinary map**, and replaces that map with
`ActorManagedI64Fields`.

All existing interpreter `object.fields.get/put/entrySet` operations
then go through that adapter. Bound `i64` members read/update the
**single canonical region cell**, not a mirror; ordinary unbound
fields keep their preexisting actor-confined layout. `put` takes one
exclusive owner loan for the entire mutation and returns the actual
old value. `entrySet` emits a by-value snapshot, not a mutable
typed-field reference. All bound `i64` values in that snapshot are
read **under one physical owner READ loan**, not separately between
competing supervisor writes; entries preserve the original declaration
order for deterministic AOT layouts and diagnostics.

There is no implicit numeric truncation: `Long`, `Integer`,
`Short`, `Byte` and `BigInteger.longValueExact()` are accepted;
out-of-range `BigInteger`, nonintegral, unknown or null types fail
closed. General `int` remains **outside** this `i64` proof slice and
is never coerced into a 64-bit cell.

This implementation does **not** mean ordinary source-language
`spawn Counter()` enters `ActorGroup.spawnCodeWorker`: it currently
uses the old unstructured source spawn path. Unsupported async,
teardown and non-managed actors continue to use actor-confined
map storage, **without granting new supervisor shared access**.
The source compiler still rejects `rt borrow self.field`, nested
projections, returned actor borrows and unsafe aliases. That is
intentional until source-level generated scoped CONTROL grants and
physical field-path borrow lowering exist.

The `ActorContext.createManagedPrimitiveStateRegion` hook is **one-use
per actor incarnation** and is only available while the source
`ActorCodeExecutor.initializeActor` callback has not yet returned.
Additional calls during that initialization, mailbox handler calls
after READY, free actors and untrusted structured workers all return
`Optional.empty()`. The one-use flag is burned before region
allocation, so a failing admission cannot be retried under a different
layout. Regression tests exercise both duplicate initialization and
mailbox-turn attempts.

## Tests (all authored, NOT_EXECUTED)

The source exact-head workflow now demands the following per-class
Surefire XML counts with zero errors, failures or skips:

| Suite | Cases |
| --- | ---: |
| `ActorStateRwLoansTest` | 27 |
| `ActorStateSupervisorLoansTest` | 40 |
| `ActorStateContainerOwnershipCompilerTest` | 13 |
| `ActorStateBorrowReturnFenceTest` | 7 |
| `ActorStateFieldBorrowBoundaryTest` | 9 |
| `ActorManagedPrimitiveRegionCodeWorkerTest` | 3 |
| `ActorManagedI64FieldMapTest` | 1 |
| **Total authored** | **100** |

The new tests exercise actual named canonical storage read by both
owner and CONTROL; read-only write denial; a foreign-region field
handle; stale CONTROL view; STOP while an owner updater is physically
active; exclusive admission for *field registration*; duplicate names;
structured `ActorCodeExecutor` code-image and manager checks; free
and **untrusted** code actors denied the managed region; the exact evaluator field
adapter without an old map copy; an overflowing `BigInteger` write
rejected without changing the cell; and a real `.atto` fixed-width
`i64` guest program that executes under the legacy unstructured path.
The new `i64` borrow negative confirms physical primitive storage
does NOT authorize source-level field-reference escaping.

The inherited returned-actor-borrow negative mutation still must
execute and produce its expected one-test failure. All source
identity, XML and mutation receipts must bind to the exact PR HEAD
and root Git tree. `steps=null` is **NOT_EXECUTED**.

## Next production gates

- Execute all 100 exact-head JUnit/Graal tests; perform a **real
  structured-source-evaluator** end-to-end integration (the two
  structured-worker tests currently use a host code executor or
  package-private evaluator adapter, not a full user-generated
  structured supervisor program).
- Prove when `ActorGroup.spawnCodeWorker` can be selected by the
  source compiler without introducing an unintended manager or
  duplicating user-supplied supervisor capabilities.
- Complete `state` type-layout lowering for signed fixed-width
  fields, safe object/ADT/index projections, callbacks, suspension
  and hot-upgrade incarnation fencing across all backends.
- Migrate/remove the trusted free-standing supervisor host
  `Future<Loan>` adapter; require physical CONTROL unwind with no
  raw references.
- Run Graal native/AOT and LLVM/.NET NativeAOT/Wasm/Forge parity;
  secure independent reviewer approval before merge.

**Private-source transfer remains blocked** by
[Java #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962),
[runner #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963),
and [conformance exposure #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
and [#77](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77).
A previously executed alternate-org **source-free** canary is not
permission to export the private Java tree. No cross-org source copy
is performed by this PR.

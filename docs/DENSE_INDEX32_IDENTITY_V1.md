# Typed generation-safe DenseIndex32 v1

## Representation

The hot physical relationship remains a 32-bit unsigned word. Zero is null;
nonzero values encode `logical_index + 1`. Java stores the bits in an `int`,
so the high bit may make the Java value negative. Treat it as an unsigned
storage word and decode with `Integer.toUnsignedLong`.

`DenseIndex32<TTag>` is a boundary-level logical identity, not a four-byte
Java object. It carries an out-of-line in-process table identity, table epoch,
encoded word and 64-bit slot generation. The wrapper is intended for in-process
API and ownership boundaries—not one allocation per relationship in a hot
SoA/AoSoA loop. It is not Serializable or a wire format. Persistence or
cross-process migration requires a separate, versioned stable table identity,
epoch and generation encoding; the in-memory Object identity is not portable.

## Table identity and lifetime

`DenseIndexTable<TTag>` owns a bounded set of rows and their sidecar generations.
A handle is live only when all of the following match:

- exact table identity;
- current table epoch;
- decoded row is in the admitted range;
- row is live;
- sidecar generation matches.

Removing a row increments its generation before reuse. A generation at
`Long.MAX_VALUE` is permanently retired, never wrapped. Clearing advances the
table epoch and invalidates all previous handles before rows are reused.
Closing permanently invalidates every handle.

`DenseIndexTable.forCurrentActor` binds the table to the current ActorRuntime
and ActorId. Its operations require that exact actor context. A table created
for one actor cannot be used by another actor, even if the compact word is
identical. The unbound constructor is for standalone host metadata use and
must not be used as actor memory authority.

## ActorDenseIndexColumns integration

`ActorDenseIndexColumns` continues to store raw four-byte words. Its legacy
`writeIndex`, `readIndex`, `readEncodedWord` and `copyEncodedColumnInto`
operations are transient row-index operations, valid only within the
caller's stable table/borrow scope. A copied word contains no table identity,
epoch, generation, actor ID or memory-access permission.

`writeHandle` verifies the actor-bound table and live generation before
storing the handle's compact word. `matchesHandle` validates the table and
generation again and then compares the current stored word. If a row was
removed and reused, the raw word may be identical but the old handle fails
the generation check.

Neither the compact word nor this sidecar grants access to actor-owned memory.
Actor confinement, borrow lifetime, scheduler turn admission and heap
provenance checks remain mandatory. Dense indices are not `ActorRef`,
`ActorManagedRef`, `Ptr<T>` or native pointers.

## Backend contract

All backends must preserve the same logical distinction:

- 32-bit dense row word is a physical table-local index;
- table/actor identity, epoch and generation are separate logical metadata;
- raw words cannot cross actor boundaries or survive invalidating table
  operations without a validated handle;
- native ABI/FFI pointers remain native-width;
- compact object references require their own heap identity and borrow proof.

Cross-backend wire fixtures must cover null, unsigned high-bit values,
maximum index, foreign table, stale generation, row reuse, epoch change,
relayout and cross-actor rejection. A backend that does not implement the
identity checks must fail closed rather than treating the word as a pointer.
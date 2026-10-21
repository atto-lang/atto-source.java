# Java/GraalVM actor arena observed access evidence v1

This is a host-side, **test-only** diagnostic for the ActorNativeArena
work in PR #1822. It does not assert that full Oreslang programs, arbitrary
object layouts, collections, continuations, or FFI allocations are instrumented.

`enableAccessEvidence(capacity)` is package-private and allowed before
the arena's first native allocation. Capacity must be 1..65536. The
recorder allocates primitive parallel arrays up front, and records no
managed object per access. When disabled it does nothing.

Successful Region byte, long and double reads/writes, approved immutable
ReadOnlyLease reads, and scalar slab read/write helpers generate one record
with an actor-relative offset, width and read/write mode. Neither native
memory pointers nor guest field values appear in the log. Failed checks
do not append success records.

`accessEvidenceSnapshot()` creates an immutable list for inspection
while synchronized on the arena. Exhaustion increments a sticky dropped
count; dropped>0 disqualifies complete evidence. Recorder buffers are wiped
and detached during physical arena close.

## Strict limitations

* Region/slot access evidence is **not** full compiler/runtime coverage.
  Variable-size text paths, native collections, external IO and other
  paths that bypass these helpers remain outside the recorder.
* The record does not yet identify the actor incarnation, logical task,
  scheduling edge, or physical execution segment. Actor-relative offsets
  must be combined with trusted actor/arena identities by the host, never
  compared across arenas as global addresses.
* No authentication, source/compiler SHA, event handoff, or synthetic guest
  trace prevention is provided here.
* Actor isolation prevents many invalid accesses but does not prove race
  freedom for allowed shared memory, concurrent actors, or stale state
  across suspension.
* The arrays and snapshot objects live on the host JVM heap in test mode;
  they are not guest state and must not be used as a near-zero-GC benchmark.

These are supporting hooks for a later trusted race evidence protocol.
A quiet or completeBuffer() result is not a `oreslang test --race` PASS.

## Verification

`ActorArenaAccessEvidenceTest` exercises the actual native Region and
lease entry points, offset identities, failed-write exclusions, bounded
snapshots and sticky overflow. Exact branch-head JDK25/GraalVM native tests,
Maven verify, Native Image, and independent review are still mandatory.

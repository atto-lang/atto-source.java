# Actor-owned scalar shards and nonparking physical turns (Java host)

Status: **experimental host runtime**, stacked on the CPU-budget and
soft-lane changes from PR #1485. It does not establish source `InvertedArray<T>`,
zero-copy inter-actor sharing, typed GPU compute, NUMA placement or LLVM
guest program parity.

## ActorShardColumns

A scheduled actor may construct a fixed-size homogeneous double region with
layout `AOS`, `SOA` or `AOSOA`. Only one layout has storage at any instant.
With AoSoA, a power-of-two tile width up to 1024 is chosen at construction.
The underlying region uses a 16 MiB per-instance limit and now reserves its
full physical byte count against both the per-actor heap ceiling and the
aggregate ActorRuntime heap ceiling before allocation. PRIVATE shards use
the existing actor-owned direct PrivateMemoryBlock (with zeroing on close /
actor termination); SHARED shards use a charged JVM array. Every reservation
is tracked by its actor cell and revoked at physical actor finalization.
UNTRUSTED allocation is rejected by this host-only API. These charges do
not yet replace general actor-arena/guest compiler allocation accounting.

Every read/write/integrate/snapshot checks the *logical actor ID* from the
currently executing actor turn, not the worker thread. This is important
because a logical actor may resume on another pthread. An actor outside
its live turn cannot call the region even if the host retained a reference.
The built-in `integrate(position, velocity, dt)` executes a dense in-actor
kernel over every row without per-entity message dispatch.
`copyColumnInto(field, destination, offset)` offers a bounded allocation-free
copy into preallocated trusted-host storage. The legacy `snapshotColumn`
signature remains only as a source-compatibility trap and now fails closed:
it cannot allocate or return an unmetered detached array. Callers must use
`snapshotColumnLeased` or `copyColumnInto` (P0 #1531).

The host-only `snapshotColumnLeased(field)` provides that first accounting
substrate: its immutable `ColumnSnapshot` reads are billed to the producing
actor's heap policy and to ActorRuntime's aggregate shared budget until
`close()` or the owner's physical finalization. Closing zeros the backing
array, clears its reference for GC, and refunds once. Finalization revokes
every outstanding handle. An out-of-actor trusted host may read/close, but a
foreign actor turn cannot read, inspect, copy or close the handle.
`ColumnSnapshot.copyInto` performs a bounds-checked copy into caller-owned
storage without exposing its backing array. The unmetered `snapshotColumn()`
compatibility signature throws and must never be lowered into guest code.
Tests: `ActorShardSnapshotLeaseTest`
(1000 retained snapshots, quota refunds, cross-actor denial and actor stop).
No general source-language or cross-domain capability is provided.

Tests: `ActorShardColumnsTest`. Manual microbenchmark:
`ActorShardLayoutBench`, comparing 1K/10K/100K rows across all layouts.
No speedup is claimed until hardware-specific measurement.

## Opt-in cross-domain nonparking physical-turn cap

Set JVM system property `ores.runtime.actor.max-active-turns=N` for N>=1.
The default 0 leaves existing scheduling behavior unchanged until the
concurrency suites certify production deployment. ActorRuntime creates one
bounded `ActorPhysicalTurnGate` shared by SHARED, PRIVATE and UNTRUSTED
actor dispatchers. It holds at most N admitted physical dispatches and
queues excess *logical* actors in a bounded FIFO without acquiring a worker.
Once the physical TurnExecutor boundary returns, the permit is transferred
to one queued actor. Close revokes pending requests and does not run their
user code; each ActorCell retains its own physical-lease and lifecycle rules.

The count conservatively includes executor-queued (not yet running)
dispatches, so under contention actual physical concurrency can be lower
than N. The gate applies to one ActorRuntime and does NOT include separate
OresScheduler guest `nb` Task executors, independent runtimes, or the
zero-thread CONTROL wake coordinator. Group ownership and memberships
remain orthogonal. Testing: `ActorPhysicalTurnGateTest`.

## Hardening still needed

- Source-compiler actor-arena allocation reconciliation, field-sensitive
  region permits and process accounting for detached/retained snapshots.
- Cooperative nonparking scoped `nb` Task admission through the same
  scheduler-domain budget, and exact physical-completion proofs.
- LLC/NUMA discovery, hierarchical work stealing, cache migration metrics,
  and cpuset hot-reload behavior.
- Source-level actor/Task/Region lowering; cross-compiler fixture parity.
- Long-running fuzz, concurrency, teardown, and exact-head Maven tests.

No benchmark numbers or passing full regression claims are asserted.

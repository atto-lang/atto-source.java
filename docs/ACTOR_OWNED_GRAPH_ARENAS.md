# Per-actor owned graph arenas (runtime substrate)

## Placement in the two-tier Graal topology

Trusted AOT supervisor / SHARED / PRIVATE actors each have their own
`ActorMemorySlice`. UNTRUSTED actors likewise own distinct slices, including
when multiple actors are scheduled into the same dedicated JIT-capable Graal
polyglot Engine. These slices are **separate semantic ownership and quota
domains, not independently GC-collected Java heaps**.

The shared UNTRUSTED Engine introduced in the parent PR #558 has one physical
collector for its groups. Contexts belonging to that Engine do not automatically
create a per-actor tracing heap. If a caller requires independent collection or
stronger OS security, it must request a separate isolated Engine/process or a
native actor-managed subheap with proven roots. The current `heapStatusFor`
correctly returns `independentlyCollectable=false` for the bootstrap runtime.
The cross-isolate actor dispatch router is NOT implemented in this PR.

## Runtime API

An actor of any kind can call its host runtime's
`ctx.actorOwnedMemory().orElseThrow().newGraphArena(maxNodes, maxEdges)`.
The result is a bounded `ActorOwnedGraphArena<T extends AutoCloseable>`.
Each arena owns its registered payload **exactly once within that arena**.
References between nodes are non-owning, generational IDs, so self-links and
bidirectional cycles need no mark/sweep or reference-count cycle collector.

On actor teardown, the slice retires each graph. It forbids further actor
operations and drops all owned payloads when outstanding leases have ended.
A retained host `Lease` prevents payload destruction but cannot resurrect
a retired actor. Closing the last lease performs deferred reclamation, and
double-close is harmless. Explicit graph close unregisters the graph from its
actor slice and returns its reserved metadata bytes, so a long-lived actor
does not collect closed-graph tombstones.

An `erase` on a borrowed node fails. Stale, recycled, or foreign node IDs
cannot be resolved or linked. The graph's fixed metadata consumes actor
quota on creation; failed allocation and early close restore that charge.
Arbitrary payloads consume additional memory and must be separately charged
by eventual compiler/backend lowering: the current quota is **not** an exact
accounting of Java object, native or FFI allocations.

## Verified placement admission

`ActorRuntime.requireActorHeapBacking(ref, requirement)` is a supervisor
admission check, not a switch that can place the actor by itself.
`OWNER_CHECKED_REGION` is supported for every kind in the current bootstrap
runtime. `DETERMINISTIC_NATIVE_ARENA`, `INDEPENDENT_GRAAL_ISOLATE`,
and `ISOLATED_PROCESS` all **fail closed** until a backend actually lowers
guest allocations or routes that actor into a verified isolate/process.
An actor declaration or a dispatcher choice can never forge a physical
heap/collector boundary. Eventually these requirements should be checked
before spawning the actor by a real placement planner.

## Security and lifecycle conditions

- APIs enforce the current **semantic ActorId**, not a physical pthread/Java
  carrier, so lane migration doesn't grant cross-actor access.
- The native graph substrate in LLVM PR #38 uses inline native owned slots;
  this Java version retains Java heap payloads and cannot disable automatic
  Graal/HotSpot GC or ensure immediate release of DirectByteBuffer backing.
- This is a host-internal object-lifetime primitive. A `Lease.get()` returning
  a Java object is NOT a compiler-proven Oreslang lexical borrow; callers
  could retain a Java reference. Guest-language exposure requires typed
  non-escaping borrows and native offset/lease lowering first.
- Do not admit arbitrary guest code as `AutoCloseable.close()`. Cleanup occurs
  in the runtime/lifecycle path; callbacks must be trusted, short,
  nonblocking and safe to execute on a possible last-lease release thread.
  Exceptions from trusted hooks are captured in `cleanupFailure()` while
  remaining payloads continue to drop. Host fatal VM errors are not swallowed.
- The current graph API detects repeated ownership *within one arena*.
  It cannot prove the same host object was not inserted into another arena,
  or that guest values have no strong Java pointer cycles internally.
  Only compiler-enforced unique ownership can provide that guarantee.
- Scheduler integration still needs an all-active-turn/suspended-continuation,
  channel/FFI/waker quiescence barrier before physical arena destruction.
  Defer native physical reclamation until an unforgeable compiler/runtime
  lifetime witness exists; do not treat this Java wrapper as that proof.

## Validation

The `ActorOwnedGraphArenaTest` suite exercises SHARED, PRIVATE and UNTRUSTED
actor-owned domains, cyclic and stale links, deterministic close, duplicate
loans, actor termination with an outstanding lease, cross-actor access,
metadata quota rollback, and failure of a single host cleanup hook.
The UNTRUSTED behavior test runs on the host actor bootstrap and is **not**
proof of Graal polyglot isolate routing.

Run with GraalVM 25:
`mvn -P '!native-thread-unix' -Dores.runtime.carriers=java
-Dtest=ActorOwnedGraphArenaTest,ActorOwnedHeapRegionTest,ActorHeapGcBoundaryTest,SharedUntrustedIsolatePoolTest test`.

Physical isolate tests need a real Oreslang language-isolate artifact and
remain intentionally skipped when that artifact is unavailable.

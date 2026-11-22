# RT region-flow analysis kernel (stage 1, experimental)

This change introduces a **standalone, fail-closed verifier of explicit compiler
region-event IR**, not a verified analysis of arbitrary Oreslang source, JVM
object layouts, GraalVM isolates, or actor runtimes. **Do not interpret a
passing report as hard real-time admission.** A caller can construct IR facts;
only a future compiler-owned, type/ownership-verified IR lowering may bind
those facts to an actual executable.

## Supported facts

The Java `RealtimeRegionFlow` model distinguishes:

- `ACTOR_LOCAL`: one actor's private mutable allocation domain.
- `SCOPED`: nested finite-lifetime regions within the same actor.
- `SHARED_IMMUTABLE`: explicit shared allocation domain, with per-actor
  authorization; no actor-private pointer export.
- `MANAGED_HEAP` and `EXTERNAL_UNKNOWN`: rejected for critical allocation
  and reference paths.

The verifier tracks finite capacities and checked allocation/copy sums,
parent-region lifetime ordering, reference stores, immutable graph edges,
mutable/shared borrow conflicts, suspension boundaries, scoped release and
use-after-release. It rejects unknown actors and unauthorized shared domains.
Nonlocal call effects must be PROVEN or explicitly CONTRACTED and have
finite copy bounds; measured/unknown evidence and unqualified heap, blocking
I/O, suspension, native FFI, exceptions and runtime code loading fail closed.

A primitive actor-to-actor transfer counts copy bytes; complex or raw object
transfers are not admitted. Only deeply immutable allocations already in an
authorized shared region can be referenced across actors. Merely freezing
an actor-local object does **not** authorize sharing it.

## Explicit restrictions

1. `Plan` and `Call` statements are **compiler-owned facts by contract,
   not an authenticated proof**. Do not expose an API that promotes
   untrusted hand-authored Plan values to hard-RT eligibility.
2. It is a **linear event-list model**, not yet an Oreslang CFG or typed SSA
   pass. No branch/loop fixed-point, exceptional-edge or whole-program
   dispatch analysis; unknown control flow must remain unadmitted.
3. `generation` is metadata only; stale actor-handle validation, capability
   revocation and runtime lease checks are still required. The model rejects
   reuse of the same region ID inside one Plan.
4. It models values, not physical pointers: ordinary JVM heap allocation,
   RTSJ ScopedMemory/NoHeapRealtimeThread, CPU affinity, actual WCET and
   schedulability require separate backend and system qualification.
5. Finite byte bounds describe event-IR totals, **not machine cycles** or
   maximum platform response time.
6. An IR model passing here does not replace the independent exact-classfile
   checker or a qualified AOT backend.

## Next compiler integration sequence

1. Add typed-IR value IDs, region IDs, owner actor IDs and source spans
   emitted *after* TypeChecker and OwnershipChecker, not caller-supplied
   externally.
2. Add branch/loop join and conservative whole-program transitive effect
   summaries. Reject unknown callees, escaping borrows, dynamic dispatch
   and effects hidden in cleanup/async continuations.
3. Carry expected call graph, resource bounds and source revision into the
   compiler manifest and exact resulting bytecode/native artifact hash.
4. Map actor-region semantics to physical actor-local memory and verify
   sharing/leases across actor shutdown, restarts, hot reload and suspension.
5. Integrate a vendor-specific no-heap execution adapter and target-specific
   timing analysis. A generic JVM test is not proof of no-heap operation.

The standalone `RealtimeRegionFlowSelfTest` is intentionally JDK-only;
it covers safe nested scopes and immutable sharing plus adversarial
cross-actor escapes, borrows, dangling scopes, exhausted budgets and effects.
It can be run with `javac --release 21` independently of GraalVM. Every
claim must still be tied to an *executed* exact-source CI run; a workflow
failure before job steps is not test evidence.

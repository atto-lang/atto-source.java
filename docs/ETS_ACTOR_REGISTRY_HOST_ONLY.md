# ETS host authority increment — 2026-11-02

## Status / hard boundaries

This increment is a **trusted host-only** ActorRuntime-scoped ETS authority, stacked on
Java draft PR #1433. It is NOT an AttoLang guest intrinsic, nor a production
nonparking actor table implementation. Keep as draft until true scheduler
integration, a trusted principal source, native/AOT checks and backend parity.

## Supported host behavior

- Registry constructed against **one** ActorRuntime; it accepts only
  ActorRuntime-issued, currently live ActorRef values from that runtime.
  Caller-authored ActorId, ActorAddress and String names are not authorities.
- Opaque per-registry TableId objects have private constructors and identity
  equality. They are never serialized or interned. IDs do not revive on slot
  reuse or retirement (fail closed).
- PRIVATE: owner read/write. PROTECTED: owner read/write, other trusted actors
  on that ActorRuntime read-only. PUBLIC: trusted actors on that runtime
  read/write. Untrusted actors denied in this host increment.
- Owner STOP immediately invalidates authorizations through runtime liveness.
  A completion callback performs only an atomic retirement mark; cleanup is
  performed on the supervisor-owned daemon reaper or an explicit host sweep.
  No map cleanup or host-lock acquisition runs in the actor done callback.
- Bounded per-registry table count; per-table entry and byte budgets inherited
  from OresEtsTable. Table data remains host-owned immutable scalars, including
  logical OresSymbol, and is not an alias to private actor heap memory.
- All table operations and destruction fail closed on actor carriers and
  ActorGroup mailman/CONTROL execution, which can suspend and must never
  be parked on ConcurrentHashMap host locks.

## Known missing production requirements

1. Host code can present a genuine ActorRef of another actor. This is an
   explicitly trusted-supervisor API, not yet a guest-safe authentication
   boundary. A guest interface must derive its principal from the actual
   logical Task/actor execution context, never a caller-supplied ActorRef.
2. Real tenant/IsolateVM ownership, physical isolation and inter-isolate
   serialization are not yet enforced. One ActorRuntime is not proof of an
   isolate boundary. Do not expose this to adversarial guest code.
3. In-flight host operations can overlap STOP/retirement. This increment
   revokes subsequent admission, but does not yet provide atomic STOP vs
   operation commit, actor-local continuation fencing, or cancellation.
4. No heir/ACK transfer, named table registry, bag/ordered table, zero-copy
   immutable leases, Task-local access grant, scheduler nonparking operation,
   or complete guest-Symbol transport integration.
5. JUnit source tests use **actual ActorRuntime** types in the source repo,
   but must be run in full-source Maven to establish runtime integration.
   Minimal-host-source mirrors with ActorRuntime stubs are NOT equivalent.

## Required next checks

- Host-only isolated JDK 25 Maven tests with exact Git blob verification.
- Full-source Java Maven/JUnit on exact PR head, and GraalVM native-image.
- In-actor positive guest tests only after a scheduler-safe operation service
  with trusted actor continuation identity and quotas exists.
- Tenant/ACL/STOP/cancellation/ABA tests across Java, LLVM, Wasm, .NET
  NativeAOT, and Cranelift with separate stepful evidence receipts.

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


## 2026-11-02 retirement admission audit

The host registry now uses one atomic 64-bit admission word per table: a high
retired bit and a bounded in-flight operation count. Each admitted operation
CAS-pins that word, checks actor and registry liveness, and releases exactly
once in `finally`/try-with-resources. Retirement seals the same word. The
supervisor reaper **cannot physically close or remove** the table until the
admission count reaches zero; the final host operation pin release also
drives reclamation after registry shutdown.

This fixes the registry's previous authorize-then-execute race **against table
retirement**: table retirement and admission are linearly ordered by their
CAS operations. It does **not** make ActorRuntime STOP itself atomic with a
table operation's logical commit. An operation that obtained its pin before
STOP may still complete after STOP. The real STOP/commit boundary must share
an ActorRuntime lifecycle/continuation gate, with scheduler cancellation
acknowledgement and independent tests. No actor carrier blocks or executes
host CHM operations; no guest ETS API has been enabled.

The added regression tests cover a deterministic forced outstanding table pin
during actor STOP, refusal of subsequent pins, deferral of physical cleanup
until pin release, 16 concurrent readers racing retirement, and stale token
denial after replacement. The forced pin uses test-only reflection instead
of a production-accessible lease API; it does not authenticate guest calls.

**CI governance:** the source repo's
`.github/private-ci-approved-repositories.txt` remains empty on `main`.
Private-to-private full-tree or subset transfers require independent
destination/source review and protected allowlisting before mirroring.
The previously reported cross-org host-subset passes do not authorize a new
copy. Existing source-org `steps=null` workflow failures do not count as
tests run.

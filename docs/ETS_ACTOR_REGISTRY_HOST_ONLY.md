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


## Real ActorRuntime integration test additions and cleanup scaling (2026-11-02)

The source-tree regression suite now includes real `ActorRuntime` tests of
self-STOP from an actor mailbox turn, structured owner cancellation, and
public-table revocation after either lifecycle transition. No guest ETS
operation is executed inside an actor turn; the table remains a trusted-host
reference whose CHM may block. Additional deterministic tests verify table
retirement with an outstanding operation pin, registry shutdown with a pin,
stale capability rejection, and cleanup/reaper scheduling races.

A resource audit found that one `ScheduledExecutorService` per registry
could create an unbounded number of daemon cleanup threads when trusted host
registries are created repeatedly. Registries now share a **single daemon
scheduled executor**. Each registry cancels its periodic cleanup future at
close, with remove-on-cancel enabled. The regression suite verifies
per-registry task cancellation/removal without relying on an unstable global
queue-size count. This bounds cleanup THREAD count, not the number of
live registries or their scheduled tasks; host admission must still control
registry creation.

**The real ActorRuntime tests are authored, not executed at this head.**
The source org's workflow runs have continued to fail before runner admission.
The private-source CI transfer contract on `main` still contains no approved
destinations. The separate source-free JDK25 canary proves only test-org runner
admission; the prior 17 passing stubbed-host tests apply to an older head.
Do not classify either as passing compiler/runtime tests for these changes.

Strictly serializing a table mutation's commit with ActorRuntime's STOP/cancel
transition still requires an independently reviewed and tested shared
lifecycle admission gate or equivalent scheduler-owned transactional
commit. Host operation admission pins only serialize against TABLE retirement,
not against STOP's transition. This is an explicit release blocker.


## 2026-11-02 callback retention / global host quota audit

A later host-only audit removed the **one `owner.done()` registration per
table**. Previously, explicitly retiring a table left its completion callback
attached to the actor's pending Future until eventual actor termination,
retaining an `Entry` and creating unnecessary work on actor finalization.
This is especially problematic under repeated table churn. Instead, direct
operations immediately check authoritative `ActorRuntime.isAlive(ref)`;
the shared supervisor reaper periodically revokes all tables belonging to
stopped/cancelled actors. Explicit `reap()` remains available for deterministic
host cleanup. No callback is executed on a carrier during ETS retirement.

The host registry now caps concurrent live registries at **1,024** in addition
to the existing per-registry table cap (1..4,096). Construction atomically
reserves a global registry slot; scheduling failure refunds it; idempotent
`close()` refunds it exactly once. This bounds the total number of periodic
reaper tasks, but **does not** certify a complete physical-memory budget.

Direct `OresEtsTable` calls now reject actor turns and ActorGroup CONTROL
mailman contexts, not only the `ACTOR_CARRIER` thread flag. Regression
tests use real `ActorRuntime` types to verify carrier refusal, actor
completion-waiter retention after table churn, and registry quota refunds.

All changes here are authored on the private source PR. **No new full-source
or subset cross-organization transfer was made.** Exact-head real Java/Graal
execution is still blocked pending independently approved destination policy
and runner admission. A source-free CI canary cannot prove these source changes
pass a compiler or the production actor runtime.


## 2026-11-02 bounded in-flight operations / STOP contention

Each table now permits **at most 256 simultaneously admitted host operations**.
Admission checks the active-count and RETIRED flag in the same atomic CAS
word, refusing permit #257 without changing any state. The operation's
try-with-resources pin release reduces the count and triggers host-only
reclamation after the retired table becomes quiescent. This is a bounded
concurrency admission policy; it is not a timeout, a fair queue, or a
scheduler-integrated nonparking guest operation path.

A deterministic reflection fixture pins 256 operations, verifies that
admission #257 fails closed, retires the table, and verifies the table cannot
be physically reclaimed until all pins drain. Another authored source test
uses the real ActorRuntime identities with 1, 10, and 100 host workers
racing owner STOP. It checks fail-closed new admission, host-worker
completion, and eventual cleanup. It **does not** establish that an operation
admitted before STOP cannot commit after STOP; that remains P0 and requires
a shared atomic lifecycle/commit boundary.

The separately executed [source-free carrier run #41397941031]
(https://github.com/networking-components-test/ncc-integration-tests/actions/runs/41397941031)
verified a model-only 256-permit seal/drain/underflow protocol and 200
contention rounds on Java 25 with nine executed steps. That is runner and
algorithm-model evidence, **not** execution of these private Java files,
real actor runtime tests, or native/AOT compiler evidence. Current private
source copying remains blocked pending independent authorization #2461.

# Actor Reliability Contract: predictable failure, bounded work, replayable diagnostics

Status: **audit specification and host-side reliability-kernel hardening**, not
a claim that all production actor runtime and OTP integrations are complete.
Java/Graal and LLVM must implement the same behavioral contract.

## Non-negotiable execution and ownership

1. Each actor has a unique runtime/actor/incarnation identity. A retired
   incarnation cannot resume continuations, settle replies, receive
   privileged CONTROL, or reuse stale ownership/lease authority.
2. The default actor is single-flight (minimum=1, maximum=1 concurrent
   executing carriers). Explicit multi-carrier actors may raise the execution
   maximum **only** when state access follows the compiler-checked
   critical/loan contract; request/message in-flight concurrency is separate
   from the number of simultaneously executing carriers.
3. Shared actors never mutate shared memory. Private actors own their heap;
   untrusted actors share one adversarial trust domain with hard per-domain
   ceilings. A bounded actor DATA mailbox does not imply permission to
   allocate unbounded deferred work, output, state or native pins.
4. A single actor admits only work for which it has real byte and item credits.
   NORMAL cannot consume CONTROL capacity or reserved supervisor-authorized
   URGENT capacity. CONTROL/STOP/CANCEL must not depend on acquiring DATA
   credits. URGENT is not CONTROL and cannot bypass a suspended actor's
   runtime continuation or the authority hierarchy.
5. A drain barrier is a **contiguous completion watermark**: out-of-order
   URGENT deliveries do not satisfy earlier admitted NORMAL messages. A
   message that was accepted but then explicitly revoked by STOP cannot be
   reported as successfully handled.

## Delivery and crash semantics

- A successful in-memory enqueue means **admission**, not durable delivery.
  A process crash, forced termination or lost transport can occur after
  admission but before handler completion. Avoid the “exactly once” claim.
- For durable or remote commands, the intended contract is
  **at-least-once attempts + incarnation-fenced deduplication** (or
  at-most-once when deliberately dropping). Exactly-once observable effects
  require a transactional inbox/outbox or equivalent atomic boundary with
  the external side effect, not merely a retry loop.
- Assign every request an opaque runtime-scoped correlation ID and a bounded
  deadline budget. One terminal outcome wins atomically among reply,
  timeout, cancellation, shutdown and crash. Stale/duplicate replies are
  rejected without unbounded ledger retention.
- An actor handler's recoverable `throw` is distinct from `raise` (which
  bypasses ordinary handlers to a recovery boundary) and `panic`
  (actor-fatal). An actor panic never terminates the VM. Cleanup/defer/finally
  run exactly once with explicit STOP/FORCE deadline precedence.
- Supervisor strategies (`one_for_one`, `one_for_all`, `rest_for_one`)
  are bounded policies, not the CONTROL authority themselves. Restart
  storms must trip a sliding-window intensity guard and quarantine; a forged
  healthy event cannot clear recent failures. Restart/backoff uses monotonic
  time and deterministic per-actor jitter to avoid synchronized retry
  thundering herds.

## Observability that cannot break production

- All diagnostic events have stable enums for event/fault class, numeric
  statistics, opaque actor/incarnation identity and monotonically published
  sequence numbers. **Never log DATA payloads, exception messages, arbitrary
  labels or user-controlled metric dimensions.** Correlation/diagnostic
  pointers have a bounded lifetime and authorized host/supervisor access.
- A diagnostic recorder is a **non-authoritative observer**. It must not
  throw on a telemetry-clock regression, saturation or backlog and thereby
  crash the actor it observes. Diagnostic anomalies increment bounded
  counters and timestamps are clamped. An active **restart policy** is an
  authority instead: clock regression fails closed.
- `System.nanoTime()` returns an arbitrary **signed** monotonic origin:
  negative readings are normal. Compare relative elapsed ticks with
  wrap-safe subtraction; never interpret the sign of absolute raw ticks.
  Sample in publication order, not before acquiring a contended lock.
- Bounded history and exact (or explicitly saturated) counters are separate.
  After sequence exhaustion, refuse duplicate trace IDs and account for
  omitted events; the runtime should remain alive. Snapshots are
  consistent/immutable and expose retained-window coverage + loss counters.
- Logs and metrics are not a synchronous dependency of CONTROL dispatch.
  Exporters must have their own credits and drop or aggregate telemetry
  rather than propagating backpressure into critical actor scheduling.

## Performance and predictable resource cost

- Explicit FIFO within each mailbox lane, bounded URGENT burst size when
  NORMAL is runnable, and runtime-continuation priority over user URGENT.
  Test fairness with deterministic workloads and tail latency under both
  empty and saturated queues.
- Bounded DATA and CONTROL admission, bounded per-actor bytes across mailbox,
  output and actor-owned state, and bounded retained Future/select arms.
  Every failed reservation must roll back the same credits atomically.
- Cooperative suspension yields physical carriers without allowing another
  actor to steal the suspended actor's state authority. CPU-bound reduction
  checkpoints provide bounded forced cooperation.
- Avoid callbacks executed on the releasing actor's mutex/continuation
  thread. Asynchronous settlement that needs actor authority must enqueue
  a continuation on that actor's fenced scheduler lane.
- Lifecycle shutdown is **physical quiescence**, not just an empty logical
  mailbox: wait for active actor turns, pending native lifetime leases,
  outstanding I/O completions and durable terminal events before closing
  Graal Context/native arenas.

## Verification / release gates

1. Prove HOST reliability primitives independently: clock anomalies,
   signed nanoTime wrap, bounded telemetry, 6000-event concurrent ordering,
   restart intensity/healthy reset, deterministic backoff and overload hysteresis.
2. End-to-end actor suites on the **exact current source tree**:
   NORMAL/URGENT/CONTROL saturation, STOP/CANCEL/FORCE races, async
   continuation re-entry, supervisor restart storms, reply-vs-timeout-vs-cancel,
   mailbox drains with out-of-order priority, and cross-actor memory budgets.
3. Fault injection and state-machine exploration: clocks advancing/regressing,
   delayed or duplicate replies, lost acknowledgments, cancellation at each
   suspension boundary, nested defer/finally, disk/network failure, partial
   FFM pin cleanup, and repeated context shutdown.
4. Performance profiles on JDK25 JVM, Graal native AOT/hybrid and LLVM:
   p50/p95/p99/p99.9 enqueue-to-handler and quiescence latency; allocations
   and peak bytes per actor; cardinality of metrics and event-drop rates;
   throughput and fairness at 1/2/4/8 schedulers, 0/1/16 urgent messages,
   and cold vs hot starts. No unverified universal “near-zero” latency claim.
5. Differential replay: same Oreslang fault/trace fixture and terminal actor
   outcomes on Java JIT/AOT/hybrid and LLVM. Unsupported native features
   must fail closed, not silently downgrade to Java policy.

## Private CI provenance security

As of 2026-10-17, source PR #1219 and incident/review-pinned CI work forbid
new cross-organization export of private actor source until the approved
source/destination snapshot policy and independent review are in force.
Do not bypass the gate just because another organization has Actions minutes.
Zero-step Actions failures demonstrate no test execution. Approved exact-tree
carriers must prove **both** repository privacy, immutable source commit/tree
identity, reviewed destination authorization, and test job steps before their
results count as source-tree evidence.

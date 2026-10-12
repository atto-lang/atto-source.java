# One shared Graal UNTRUSTED Engine for Oreslang actors

Architecture decision: **two execution trust domains**, not one isolate per
actor: the trusted JVM/Native Image host, and ONE Graal polyglot Engine with
`SandboxPolicy.UNTRUSTED` and `spawnIsolate(true)` for all ordinary
UNTRUSTED actors.

## PR #569 revision

The old `DedicatedUntrustedActorIsolates` code allocated a new `Engine` and
garbage collector per actor, contradicting the application contract. It is
replaced by `SharedUntrustedActorSupervisor`. All Engine creation is
centralized in `SharedUntrustedIsolatePool` (PR #558). The supervisor
provides bounded opaque actor leases, generation-fenced scalar actor
mailboxes, readiness/done/cancellation, and context cleanup.

An ordinary actor currently owns **one guest Context**, which is *not*
an isolate. Multiple contexts share the **same physical Engine/GC/JIT**.
Guest startup, parse, link, compilation and actor state stay in the sandbox.
Per-context budgets and private actor-owned state do not imply independent
per-actor GC, fault isolation, or a killable independent native heap.
The shared Engine's aggregate heap ceiling is separate from individual
context limits, and the guest can pause other untrusted actors during GC.

```java
try (var actors = new SharedUntrustedActorSupervisor(256L << 20, 16);
     var a = actors.spawn(source, "worker-a", "UntrustedWorker");
     var b = actors.spawn(source, "worker-b", "UntrustedWorker")) {
    a.send("work");  // copied and UTF-8 bounded scalar transport only
    b.send("work");
    // The actors have different Contexts but share one physical Engine.
}
```

Prefer sharing an externally owned `SharedUntrustedIsolatePool` with the
hot-reload manager and other trusted supervisor components when those are
composed into one application; two independently constructed pool instances
are explicitly forbidden while the first remains live. A pool is created
lazily in the convenience constructor to preserve validation-before-native
resource-allocation guarantees.

`ActorRuntime.HeapIsolationRequirement.REQUIRE_INDEPENDENT_COLLECTOR`
remains fail-closed for **all** actor kinds in the ordinary Truffle runtime:
private actor state and independent scheduler lanes are NOT independently
collected Java heaps. It is intentionally *not* satisfied merely by running
several actors in the single shared UNTRUSTED Engine. The
`--ores-actor-heaps=independent` CLI guard records this explicit requirement
and rejects unsupported placement instead of silently downgrading it.

## Isolation guarantees and limits

- Exactly one shared `Engine` for all untrusted actor Contexts in this
  application classloader; a pool singleton fence forbids ad-hoc Engines.
- Unique actor identity and generation; no guest/host object references,
  borrowed addresses, direct buffers, Java callbacks or mutable graph handles
  may cross the copied scalar transport.
- The supervisor may close one actor's Context via `Context.close(true)`
  without owning that Context's guest-entry lock. Such closure invalidates
  future host actor admissions and keeps other actors' Contexts alive.
- Resource bounds apply at the aggregate isolate (heap), Context (cumulative
  CPU/heap/thread/output) and source actor (fuel/mailbox/state) levels.
- Fatal isolate failures or a shared GC stall can affect *all* untrusted
  actors. In-process Graal polyglot isolate policy is not a separate OS
  process boundary. Higher-assurance multi-tenant fault isolation is a
  distinct deployment tier, not the default actor model.

## Tests and remaining release gates

`SharedUntrustedActorSupervisorTest` verifies configuration, fail-closed
admission, physical Engine sharing, separate Contexts, distinct actors,
copied scalar rejection, source execution, and independent Context
retirement. The latter tests are guarded by
`ORES_TEST_SHARED_UNTRUSTED_ISOLATE=true` and MUST run against an actual
Oreslang native language-isolate artifact. CI in
`.github/workflows/untrusted-isolate-e2e.yml` fails if any gated test
skips; ordinary classpath JUnit tests do not prove physical isolation.

The explicit host API is functional only with the isolate artifact. The
compiler's automatic source `spawn UntrustedWorker()` in a trusted Context
continues to fail closed until remote ActorRef, ready/done Futures,
structured cancellation, supervision and source type semantics can be
routed transparently. PR #558, which itself is draft, is this PR's base.
Do not merge or mark ready on runnerless checks, unexecuted integration
tests, or until the base branch's current conflicts are reconciled.

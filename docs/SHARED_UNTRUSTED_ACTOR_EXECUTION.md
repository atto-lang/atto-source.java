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
composed into one application. For example:

```java
try (var pool = new SharedUntrustedIsolatePool(256L << 20, 16);
     var loader = HotReloadManager.forUntrustedActors(
             IsolatePolicy.developer(), ExecutionProfile.serverJit(), pool);
     var actors = new SharedUntrustedActorSupervisor(pool, 16)) {
    // Loader Contexts and actor Contexts use the SAME Engine/GC/JIT.
    // Pool lifetime belongs to this outer try-with-resources scope.
}
```

Two independently constructed pool instances are explicitly forbidden while
the first remains live. A pool is created lazily in the convenience
supervisor constructor to preserve validation-before-native-resource
allocation guarantees. This initial implementation allocates a lightweight
guest Context per actor; further Context pooling is a separate optimization
and must not allocate a new physical Engine or degrade actor isolation.

`ActorRuntime.HeapIsolationRequirement.REQUIRE_INDEPENDENT_COLLECTOR`
remains fail-closed for **all** actor kinds in the ordinary Truffle runtime:
private actor state and independent scheduler lanes are NOT independently
collected Java heaps. It is intentionally *not* satisfied merely by running
several actors in the single shared UNTRUSTED Engine. The
`--ores-actor-heaps=independent` CLI guard records this explicit requirement
and rejects unsupported placement instead of silently downgrading it.

## Pooled, content-addressed immutable code images (follow-up draft)

`SharedUntrustedIsolatePool` owns an `UntrustedSourceImageCache`, distinct
from the trusted process code store. Both `UntrustedJitCodeUnit` and untrusted
`HotReloadManager.Generation` acquire their `Source` through the same pool.

- Source cache identity is the exact pair (logical code-unit ID, SHA-256 of
  UTF-8 source). Equality is rechecked against the original bytes/text on hits.
  Random actor UUIDs are still minted for invocation/revocation fencing, but
  are **not** part of the Source name or code cache key.
- All matching live code units in one pooled untrusted Engine receive the
  same immutable host-side `Source`. Different source versions and import
  identities never alias. Different pools do not share Source references.
- Each actor/hot-reload generation still has an independent Context and
  mutable interpreter/module state. Nothing here promotes actor-private data
  or pointers to cross-actor memory, or authorizes FFI/host capabilities.
- Each Context pins the Source through a lease until **confirmed**
  `Context.close(true)`. Failed close retains the lease and capacity;
  successful last release evicts the Source for reclamation.
- This prototype caps simultaneously live distinct source images at 512
  and total pinned source text at 16 MiB (each text <= 1 MiB UTF-8).
  Quotas are enforced before insertion. Source images do not allocate a
  new Graal Engine, isolate, JIT, or heap for each actor.

**Scope and unverified claims:** This draft shares immutable
`org.graalvm.polyglot.Source` identities inside the untrusted pool, switches
`OresLanguage` to `ContextPolicy.SHARED`, and moves the mutable
`OresEvalRootNode.Evaluator` from a root field into per-`OresContext`
identity-keyed storage. This permits the Engine to reuse parsed call targets
without reusing another actor's mutable evaluator, but actual parse counts,
JIT code residency, concurrency safety and physical RX-page sharing still
require exact-head executed tests. Code-page deduplication is **not yet
proven**. The shared cache must never be exposed to the trusted
Engine/compiled-code domain.

This first integration covers explicit guest-source actors and untrusted
hot-reload staging. Compiler-owned import closure resolution and
`core/alloc/actor/std` preloading still must route individual approved
library units through a domain-local immutable image/cache. A freestanding
build must never pull in hosted stdlib by default. Merely sharing these
host-side Source objects is not evidence that every stdlib function is loaded
once as native machine code.

The one untrusted Engine remains one physical trust/failure/GC domain. It is
not hard isolation between hostile tenants. Stronger tenant or supervisor
separation requires a policy decision; never import the process-global
`SharedCodeRegistry` approval allowlist from historical Java #416
(issue #493 documents its cross-tenant approval-scoping gap).

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

## Post-audit hardening (2026-10-12)

**Exactly-once teardown:** `VerifiedCloseFence` waits for confirmed native
Context/Engine retirement and preserves failure results for concurrent and
repeat callers. An untrusted actor or hot-load generation whose Context
fails to close remains counted against admission capacity, with its handles
fenced. A manager/supervisor cannot claim successful shutdown while
its retirement is still in progress. `SharedUntrustedActorSupervisor.close()`
also waits for already-admitted pending spawns, including when the
application (not the supervisor) owns the shared pool.

**Bounded hostile source admission:** `GuestAdmissionDeadline` sets a
30-second upper bound for source parse/link/initialization and initial
actor creation, which otherwise inherit the group's much longer cumulative
CPU limit. Expiry launches `Context.close(true)` outside the guest entry
monitor; failed cancellation is fail-closed (the reservation remains
occupied). A daemon timer performs admission expiry decisions, dispatching
native revocations on separate virtual threads so one stalled shutdown does
not block other deadlines. The 30-second limit is a host admission
deadline, *not* a guarantee that a blocked JVM/native close finishes by
that time; that must be verified under native-image cancellation tests.

**Priority force cancellation:** a supervisor actor proxy exposes
`forceCancel()` in addition to cooperative `cancel()`.
`forceCancel()` retires that actor's Context, not the shared Engine,
and returns successfully only after Context close was confirmed. It
does not require the guest Context's entry monitor; the cooperative
`cancel()` path can wait behind a hostile guest turn. The sibling actors
are not deliberately cancelled, but GraalVM warns that cancelling one
Context may negatively affect other Contexts sharing its Engine.

**Security limits:** an in-process Graal language isolate is not an OS
process boundary. A shared isolated Engine also does not provide hard
fault independence between arbitrary untrusted tenants. Per-actor
logical arenas, Contexts, and watchdogs do not imply a separate native
collector or isolated system-call authority per actor.

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

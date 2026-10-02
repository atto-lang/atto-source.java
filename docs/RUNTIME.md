# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. Hot reload never requires loading executable native libraries.
8. Every hot-loaded generation is a fresh guest context and may be mapped to a stronger Graal/native isolate by the production host.
9. `self` cannot be rebound.
10. An actor has one mailbox and never executes two mailbox turns concurrently.
11. Private and shared actors use separate dispatcher thread pools.
12. Private actor transport rejects synchronized shared-memory cells.
13. Shared actor state is still actor-owned; ordinary actor field mutation is serialized by the mailbox, not by implicit locks.
14. Each private actor starts with a 1 MiB VM commitment and grows only through the runtime memory governor.
15. Actor-requested and VM-triggered growth are both bounded by per-actor and aggregate runtime ceilings; growth may be denied.

## Deployment matrix

| Profile | Host | Guest execution | Hot reload |
| --- | --- | --- | --- |
| JIT | JVM/GraalVM | interpreter -> Truffle JIT | fresh source generation |
| AOT | Native Image | precompiled interpreter | fresh source generation, no executable-code load |
| HYBRID | Native Image | interpreter -> guest JIT where supported | fresh source generation |

iOS is treated as AOT-only by the execution-profile validator. Android may use AOT or another profile where platform policy allows it.

## Why hot reload is source/IR based

Native Image is fundamentally closed-world for Java classes. Oreslang therefore does not make hot reload depend on dynamically linking new Java/native code. The runtime/interpreter is part of the shipped artifact; newly downloaded Oreslang source (and later a stable serialized Ores IR) is treated as untrusted data, validated, then executed in a new generation.

That makes the mechanism consistent across Windows, macOS, Linux, Android, and AOT-only targets. Platform-specific native dynamic linking can remain an optional trusted-host optimization, never a semantic dependency.

## Capability ownership

Capabilities belong to a launch policy, not to source code. Source may eventually declare required capabilities for diagnostics, but declarations will never grant them.

The strict production direction is:
- parent supervisor owns maximum authority;
- child isolate/actor receives an equal-or-smaller capability set;
- no child may escalate its own policy;
- cross-actor values must pass sendability/freezing rules;
- hot-loaded code gets a new generation and new policy admission.

## Receiver implementation

Method code is stored once per class declaration. Direct calls dispatch to that definition with the receiver as an implicit immutable argument. Only first-class method extraction allocates a bound method pair. This provides Go-like receiver safety without allocating a closure for every instance or every direct method invocation.


## Actor dispatchers

The host actor runtime follows the same scheduling shape as Akka's event-based dispatcher: many actors share an executor, each actor has its own mailbox, and a scheduled actor drains only a bounded number of messages before yielding back to the executor. The configured throughput bound prevents one hot mailbox from monopolizing a worker.

Oreslang deliberately uses two executors:

- **private dispatcher** — private actors, isolation-copy message transport;
- **shared dispatcher** — shared actors, immutable sharing plus explicit `SyncCell<T>` shared state.

A per-actor atomic scheduling gate ensures only one drain task for that actor is active. The executor may run different turns on different threads; thread identity is never actor identity.

The runtime does not interrupt a carrier thread to stop one actor because that thread belongs to the dispatcher and may subsequently execute unrelated actors. Actor cancellation is observed at compiler-injected scheduler safepoints. Whole-runtime shutdown may interrupt the dispatcher executors.


## Private actor memory confinement

A private actor is assigned an `ActorMemorySlice` when it is created. The slice is keyed by actor identity rather than by dispatcher thread because actor turns may migrate between worker threads.

Memory has three distinct quantities:

- **used bytes** — live mailbox and actor-state reservations;
- **committed bytes** — capacity currently granted to this actor by the VM;
- **hard limit** — the actor's `IsolatePolicy.maxHeapBytes()` ceiling.

A private actor starts with **1 MiB committed** by default. Commitment is elastic: the default policy grows geometrically in at-least-1-MiB quanta (1 MiB -> 2 MiB -> 4 MiB -> 8 MiB, and so on) until the actor hard limit or aggregate runtime limit is reached.

Growth is deliberately two-way:

1. **actor initiated** — actor code may request additional capacity through its private memory handle and receives an explicit approved/denied result;
2. **VM initiated** — an allocation that would cross committed capacity triggers required growth automatically, and crossing the configured high-water mark (80% by default) triggers best-effort proactive growth.

Every growth attempt passes through the host `MemoryGovernor`. The governor may deny growth even when static limits still have room, for example because of process pressure, tenant quotas, supervisor policy, or deployment-specific resource controls. Static per-actor and aggregate runtime ceilings are always enforced in addition to the governor; a governor can make policy stricter but cannot enlarge those ceilings.

Private mailbox admission is:

1. reject explicitly shared mutable handles such as `SyncCell<T>`;
2. isolation-copy/freeze the message graph;
3. conservatively estimate its logical Oreslang heap size;
4. ensure the destination actor has enough committed capacity, requesting VM growth when necessary;
5. reserve the message bytes and enqueue only after memory admission succeeds;
6. release transient mailbox bytes after the mailbox turn completes.

Persistent generated actor state reserves from the same slice. Actor teardown releases both live usage accounting and all remaining committed capacity.

The JVM backend's region is **actor-confined rather than Java-thread-confined**. A private actor may execute different turns on different dispatcher threads, so a single `Arena.ofConfined()` tied to one carrier thread would violate the scheduling model. The current JVM backend therefore enforces actor ownership and capacity in the runtime; a hardened backend may map the same contract to actor-owned region/chunk allocation or to a Graal/native isolate for physical heap separation without changing source semantics.

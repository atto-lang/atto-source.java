# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. Hot reload never requires loading executable native libraries.
8. Production multi-tenant hosts use one isolated Graal `Engine` per tenant; hot-loaded contexts for that tenant share that Engine/isolate.
9. Actors choose `SHARED_HEAP` or `PRIVATE_ARENA` independently at spawn time; neither mode may cross the tenant policy ceiling.
10. `PRIVATE_ARENA` is a bounded, growable set of `Arena.ofConfined()` native segments owned by one stable actor virtual-Thread identity, not a second Graal isolate, Java GC heap, or OS address space.
11. Raw `MemorySegment` handles are not sendable across actor boundaries.
12. `self` cannot be rebound.


## Tenant and actor memory topology

The deployment unit is deliberately two-level:

1. **Tenant/customer:** one `TenantRuntime` owns one Graal polyglot-isolated `Engine`. That Engine supplies the physical Java heap, GC, and JIT fault domain for the tenant.
2. **Actor:** actors inside the tenant choose between semantic isolation on the tenant heap and a private native arena.

```text
host JVM / OS process
└─ tenant Graal isolate
   │
   ├─ PRIVATE_ARENA actors
   │   ├─ actor A virtual Thread ── Arena.ofConfined() ── private native heap
   │   ├─ actor B virtual Thread ── Arena.ofConfined() ── private native heap
   │   └─ ...
   │        JVM virtual-thread carrier scheduler
   │        adaptive target parallelism: 20..40 platform threads
   │
   └─ SHARED_HEAP actors
       ├─ actor C serial mailbox turns
       ├─ actor D serial mailbox turns
       └─ ...
            separate Oreslang ForkJoinPool
            adaptive target parallelism: 10..20 platform threads
```

A private actor owns one **stable virtual `Thread` identity** for its lifetime. Its confined arena is created by that virtual Thread. The JDK may mount and unmount the virtual Thread on different carrier platform threads, but that does not change the owner Thread identity, so `Arena.ofConfined()` remains JVM-enforced. A different actor Thread still receives `WrongThreadException` if a raw segment somehow leaks. Oreslang additionally rejects raw segments at mailbox/send boundaries.

This is intentionally goroutine-like scheduling: many private actors may exist, while only a bounded number of carrier platform threads execute runnable private actors at once. Java 25 exposes one process-level virtual-thread scheduler; Oreslang tunes its target parallelism between 20 and 40. Shared actors do not consume virtual Threads: they execute bounded serial mailbox turns on a different pool that is tuned between 10 and 20 workers.

A `PRIVATE_ARENA` actor's **mutable Oreslang state must be arena-backed**. Generated private-actor state may not retain mutable Java-heap closure state; otherwise the actor would have a private native arena without a truly separate logical state heap. Runtime control metadata such as `ActorRef`, mailbox queues, and scheduler bookkeeping may remain managed runtime objects.

`MemoryPolicy.privateArena(initialBytes)` commits only the initial segment. If an allocation does not fit, the actor allocator adds another segment, normally growing geometrically (1 MiB -> 2 MiB -> 4 MiB, for example). Existing segments are never relocated, so previously returned actor-local pointers remain stable. Growth stops at the actor's `IsolatePolicy.maxHeapBytes()` hard limit and at the tenant/runtime aggregate private-arena budget. A hungry actor can therefore expand when capacity is available without pre-reserving its worst-case footprint or starving neighboring actors.

`capacityBytes()` reports currently committed native memory, `maxCapacityBytes()` the actor hard limit, `usedBytes()` the consumed bytes including alignment padding, and `segmentCount()` the number of committed backing segments. All segments are released together when the actor exits.

A confined arena is **not** an OS-process address-space boundary. For protection against arbitrary native memory access by hostile code, use a separate tenant Graal isolate/process. The private-arena actor mode is a stronger actor-local ownership and FFM thread-confinement boundary inside that tenant isolate.

The arena path requires Oreslang value representations to opt into the actor allocator. Merely selecting `PRIVATE_ARENA` does not magically relocate arbitrary Java objects; runtime/compiler lowering must use `ActorContext.memory().allocate(...)` for arena-backed values.

GraalVM Native Image 25 supports the FFM foreign-memory APIs, so the same scheme can live inside the tenant polyglot isolate image.


## Actor scheduling

The default JVM-level scheduler bounds are configurable before Oreslang runtime initialization:

```text
-Dores.actor.private.carriers.min=20
-Dores.actor.private.carriers.max=40
-Dores.actor.shared.pool.min=10
-Dores.actor.shared.pool.max=20
```

For private actors, the runtime also seeds the JDK virtual-thread scheduler properties `jdk.virtualThreadScheduler.parallelism` and `jdk.virtualThreadScheduler.maxPoolSize` when the embedding host has not already set them. On Java 25, `VirtualThreadSchedulerMXBean` is used to raise/lower target parallelism within the configured private-actor bounds according to runnable virtual-thread pressure.

The shared-actor pool uses FIFO-oriented `ForkJoinPool` scheduling and bounded actor turns. At most one turn for a given actor can execute at once, but successive turns may run on different shared-pool workers.

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

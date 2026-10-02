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
10. `PRIVATE_ARENA` is a bounded `Arena.ofConfined()` native block owned by the actor Thread, not a second Graal isolate or Java GC heap.
11. Raw `MemorySegment` handles are not sendable across actor boundaries.
12. `self` cannot be rebound.


## Tenant and actor memory topology

The deployment unit is deliberately two-level:

1. **Tenant/customer:** one `TenantRuntime` owns one Graal polyglot-isolated `Engine`. That Engine supplies the physical Java heap, GC, and JIT fault domain for the tenant.
2. **Actor:** actors inside the tenant choose between semantic isolation on the tenant heap and a private native arena.

```text
host JVM / OS process
└─ tenant Graal isolate
   ├─ shared actor ---- managed objects on tenant isolate heap
   ├─ shared actor ---- managed objects on tenant isolate heap
   └─ arena actor ----- Arena.ofConfined()
                        └─ bounded MemorySegment + Oreslang allocator
```

A confined arena is created **inside the actor's own virtual Thread**. Java's FFM access checks therefore reject dereference from another Thread, even if a host bug leaks a `MemorySegment` reference. Oreslang additionally rejects raw segments at mailbox/send boundaries.

The arena path requires Oreslang value representations to opt into the actor allocator. Merely selecting `PRIVATE_ARENA` does not magically relocate arbitrary Java objects; runtime/compiler lowering must use `ActorContext.memory().allocate(...)` for arena-backed values.

GraalVM Native Image 25 supports the FFM foreign-memory APIs, so the same scheme can live inside the tenant polyglot isolate image.

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

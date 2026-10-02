# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. `actor.gc()` is actor-local and may never silently escalate to process-wide GC.
8. `process.gc()` is capability-gated and is the only guest API allowed to request host/process-wide GC.
9. Hot reload never requires loading executable native libraries.
10. Every hot-loaded generation is a fresh guest context and may be mapped to a stronger Graal/native isolate by the production host.
11. `self` cannot be rebound.

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


## Garbage-collection boundaries

Oreslang treats an actor as a memory-ownership and GC boundary.

- automatic reclamation remains the default;
- actor exit makes the actor cell, mailbox, and actor-owned reachable graph reclaimable together;
- `actor.gc()` requests collection of the current actor only;
- `process.gc()` requests whole-runtime/host-process collection and requires `PROCESS_GC`;
- immutable shared values and backend-managed large blobs may outlive one actor and are reclaimed according to their shared ownership/reference policy.

The current JVM/Truffle reference backend cannot ask HotSpot/Graal GC to trace only one arbitrary Java actor object graph. Its default `actor.gc()` backend is therefore a cooperative actor-local collection point and **does not call `System.gc()`**. Native/isolate backends plug in a collector that can physically reclaim the actor heap. The JVM backend uses `System.gc()` only for explicit, capability-authorized `process.gc()`, and even there the JVM treats it as a collection request/hint rather than a correctness requirement.

This preserves the language contract now without faking actor-local GC by introducing a global pause.

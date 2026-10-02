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
10. A `singleton module` has exactly one actor-owned state cell per OS process, not per Graal context/isolate.
11. Importers/callers receive only a proxy/handle; mutable singleton state never leaves the singleton actor.
12. Singleton call arguments and results pass the normal sendability/freezing boundary, and singleton calls are request/reply operations.

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


## Process-wide singleton modules

`define singleton module name` is implemented by the process singleton registry. The registry is static process-lifetime runtime state, independent of any one `OresContext`. The first access creates one virtual-thread actor with a serial mailbox and initializes the module bindings on that actor. Later contexts and isolates resolve the same canonical module key to the same actor identity. The key is based on the explicit source namespace when present; otherwise it includes the source code-unit identity plus module name, so unrelated files/tenants that happen to use the same module name cannot alias one another.

Public functions are exposed through a typed module proxy. A call such as `await config.read()` enqueues a request to the singleton actor and waits for its reply. Calls made by singleton code to another function in the same singleton module are direct calls against the already-owned state environment, so self-calls do not enqueue to the same mailbox and cannot deadlock.

Singleton fields are always actor-private. They may be `const`, `val`, or mutable `let`, but cannot be `pub`; callers must use exported functions. Borrows and function/closure values are rejected in public singleton signatures, and the runtime freezes arguments/results to prevent writable aliases from crossing the actor boundary.

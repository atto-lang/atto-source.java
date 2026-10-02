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


## GPU placement boundary

`gpu` source is admitted as a distinct execution target. It is not a performance annotation on ordinary Truffle execution.

The front end first type-checks the program and then runs GPU admission. GPU admission is intentionally conservative: it proves that kernel bodies stay inside the current device-safe subset and that statically resolved calls from GPU code target other GPU callables. Dynamic host calls, object graphs, host references, unsupported ABI types, and host/global captures are rejected.

The reference evaluator does not emulate GPU work on a CPU. If a GPU-targeted named function, static function, lambda, or `gpu parallel` batch is invoked without a configured GPU lowering/backend, execution fails closed. This avoids tests or development runs accidentally validating CPU behavior that would not be legal device behavior.

`gpu parallel` is scheduling intent for independent kernels/work-items. Backends remain responsible for launch geometry, lane/warp/wavefront/work-group assignment, occupancy, fusion, batching, and device selection. Oreslang does not expose stable "GPU core N" affinity because that is not a portable hardware abstraction. Physical device affinity can be added separately once the runtime has a real multi-device scheduler.

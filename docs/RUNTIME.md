# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access, host class lookup/loading, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
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

## Java host interop boundary

Java imports are a two-key boundary: the Oreslang policy must grant `FFI`, and the embedding Context must independently allowlist the exact fully qualified Java class. The restricted builder configures `allowHostClassLookup` from that set and always disables host class loading, so guest code cannot extend the class path.

When a host class is allowlisted, the embedder constructs a narrow `HostAccess` policy from that class's explicitly enumerated public declared constructors, methods, and fields. Access inheritance is disabled. This intentionally avoids `HostAccess.ALL` and prevents ambient inherited reflection helpers such as `Object.getClass()` from becoming available merely because one safe class was admitted.

FFI does not subsume the rest of the capability model. Filesystem, network, concurrency, and native-facing JDK packages require the corresponding Ores capabilities in addition to `FFI`. VM-control/reflection infrastructure such as `System`, `Runtime`, `Class`, `ClassLoader`, `java.lang.reflect.*`, `java.lang.invoke.*`, and JDK-internal packages are rejected from class-level FFI imports entirely; exposing those safely requires a future reviewed member-level bridge rather than a whole-class allowlist.

The host-side policy builder may load an explicitly configured class in order to enumerate its members. Guest-language resolution never does that: Oreslang calls `TruffleLanguage.Env.lookupHostSymbol`, which re-applies the Context host-class filter. A class missing from the allowlist therefore fails at the Truffle boundary even if source text names it.

Adversarial FaaS isolates fail closed and do not enable Java host imports. A future production FFI profile can introduce a reviewed class/member manifest rather than widening this default.

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

# Oreslang hard real-time Java target

Status: architecture and initial admission scaffolding. The current Java/Graal runtime is not hard-real-time certified.

## Execution profiles

The Java backend must distinguish:

- `java-hard-rt`: qualified real-time Java runtime, no ordinary managed-heap access from the certified path, full guest AOT when required by the deployment, bounded runtime services.
- `graal-aot`: GraalVM Native Image deployment. Full host AOT does not by itself prove full guest AOT, zero GC, or hard real time.
- `java-standard`: regular JVM/Graal execution.

Do not silently downgrade a requested hard-RT build to one of the weaker profiles.

## RTSJ adapter direction

Add a runtime adapter for Real-Time Specification for Java implementations. Keep vendor-specific bindings behind a small capability interface.

Candidate mappings:

- Oreslang RT worker -> real-time/no-heap thread primitive;
- actor arena -> scoped/no-heap memory where supported, otherwise explicitly managed off-heap region;
- periodic task -> release/period parameters;
- priority/deadline -> runtime scheduling parameters;
- actor/task memory budget -> scoped-region/pool capacity;
- immutable shared region -> explicitly admitted long-lived region.

The adapter must probe actual capabilities at startup/build time and fail closed when a requested guarantee is unavailable.

No source-level Oreslang semantic should depend directly on a vendor class name.

## Actor arenas and GC

An actor-local logical arena implemented with ordinary Java objects remains part of the JVM managed heap. It does not make the actor independent of JVM GC.

For `java-hard-rt`, actor state must therefore use one of:

1. qualified RTSJ scoped/no-heap memory;
2. explicitly managed off-heap layouts/handles;
3. another VM facility with equivalent proven timing/isolation properties.

The Java heap may still exist for build-time/general runtime services only if it cannot pause or otherwise interfere with the certified execution domain.

Critical-path code must avoid creating wrapper objects, temporary futures, exceptions, collections, log messages, reflection metadata, or Truffle interpreter state.

## GraalVM full-AOT distinction

Native Image can ahead-of-time compile the Java host implementation, but this is not sufficient if Oreslang guest code is still interpreted or JIT compiled.

Strict `full-aot` requires the reachable Oreslang guest graph to be compiled before deployment with no guest interpreter/JIT fallback on the admitted path.

The current Maven `native-aot` profile is therefore an important build substrate, not yet proof of strict guest full-AOT.

## Initial admission scaffold

`RealtimeProfile` records the first backend-side fail-closed admission properties and shares diagnostic codes with the common contract.

It rejects:

- managed heap access;
- tracing collector interference with the certified thread;
- runtime code generation;
- unknown FFI;
- unbounded allocation/blocking/loops/cleanup;
- page-fault risk;
- missing WCET/deadline budgets;
- response bounds where WCET + blocking + jitter overflows or exceeds the deadline;
- missing independent whole-system schedulability evidence for the exact declared scheduler/budget;
- missing peak-memory evidence, or a proven peak larger than `maxMemoryBytes`.

The common contract allows period/minimum inter-arrival time only where applicable, so `periodNs == 0` remains valid for an aperiodic unit whose independent scheduler proof covers that workload. This scaffold also does not invent a constrained-deadline rule: a deadline greater than a period is not rejected locally if the supplied scheduler proof establishes schedulability.

A zero-byte `maxMemoryBytes` is valid when the compiler/runtime proves that the reachable critical path has a zero-byte peak. The cap is therefore checked against an independent peak-footprint witness instead of treating zero as "missing".

The public two-argument `admitJavaHardRt(effects, budget)` entry point now deliberately fails closed because it has no scheduler or peak-memory proof. The proof carrier and proof-bearing overload are package-internal so external callers cannot self-certify by constructing a `Proof(true, ...)`. A future trusted compiler/runtime bridge must mint the witness only after validating the exact budget, scheduler, deployment and peak-memory bound.

The next compiler step is transitive effect inference from guest source plus a compiler-derived peak-memory witness, with the deployment scheduler supplying the independent schedulability result.

## Vendor/runtime work

Create a separate `oreslang-runtime.java-realtime` repository only when the first real RTSJ/vendor adapter is implemented. Keep the language/compiler contract in this repository and `oreslang-common-source`; the new repository should contain vendor/runtime integration, packaging, probes, and conformance tests rather than duplicate compiler semantics.

The first qualification target should exercise:

- no-heap/scoped actor state;
- periodic worker release/deadline behavior;
- bounded mailbox handoff;
- cancellation/cleanup;
- full-AOT artifact if the VM supports the required mode;
- zero managed allocation on the certified steady-state path;
- interference tests while the ordinary heap is under GC pressure.

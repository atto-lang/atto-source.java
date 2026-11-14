# Java/GraalVM adaptive trace code retirement and memory gate — 2026-11-13

Status: host control-plane implementation candidate on top of tracing PR #3035. No AttoLang compiled machine-code generation, Native Image, true actor cutover or OTLP exporter is demonstrated by this patch.

## Changed host boundaries

- A supervisor-retired adaptive code dispatcher now detaches its final active executable and retains only code generations pinned by still-running logical invocations.
- Closing the last pinned invocation on a retired dispatcher leaves no retained executable generations. Repeated STOP and double-close are idempotent.
- The logical invocation object is allocated **before** incrementing the generation pin counter, so allocation failure cannot strand an uncloseable pin.
- Revocation of a trace policy while compilation is pending invalidates the ticket even when the same probe mask is later re-enabled (policy-version ABA fence).
- Supervisor policy mutation and code publication are serialized on the policy monitor. Compiler work and guest callbacks do not run under this monitor.
- The release-side pruning path uses indexed iteration rather than allocating a capturing lambda on each generation cleanup.

## Evidence and no-GC boundary

`TraceJitRetirementSmoke` exercises these invariants in a standalone JDK run, including 64 publication-vs-policy-change races and 32 competing-carrier double-close races. It measures host thread allocation over 20,000 pin/close operations after 10,000 warm-up iterations, **only when** the JDK ThreadMXBean supports measurement. Unavailable counters print `UNAVAILABLE`, never zero. There is no asserted universal allocation threshold and no forced garbage collection.

The dispatcher still allocates one Java `Invocation` per logical pin; a static `VarHandle` now replaces the additional per-pin `AtomicBoolean` wrapper without weakening thread-safe idempotent release. This is **not** a zero-GC hot path. The next memory milestone must map invocation lifetime into an owner-checked, preallocated actor continuation/frame and prove cancellation and exception drop paths before replacing this safe host substrate.

A separate per-actor code-image ledger is in PR #3067. This patch does not wire every invocation into that ledger, and its internal generation count is not an independent physical-unload authorization. Proven physical retirement still requires actor, continuation, FFI, callback, and IO quiescence.

## CI and production gate

`.github/workflows/trace-jit-retire-exact-head.yml` runs source-head/tree checks, standalone JDK 21-compatible `javac -Xlint:all -Werror` and both smoke runners, plus exact-head Maven JUnit on GraalVM JDK 25. A source-org run with `steps=[]` must be recorded as **NOT_EXECUTED**.

Do **not** claim full guest AOT or hot upgrade until immutable typed-IR/linker target evidence proves compiler-generated executable AttoLang callables and a trusted JIT compiler actually produces an authenticated `Candidate`. Native Image host AOT remains separate. Do not mirror this private source to another org before the repository's independent source-transfer approval gates pass.

Next test matrix: actual .atto task suspension/resume, STOP, cancellation, actor restart, multiple generations under repeated hot reload, native-image and JVM allocations per guest message, RSS and GC-pause distributions, and 32-bit owned arena offset/drop safety.

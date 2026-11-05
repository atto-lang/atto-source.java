# Second five Java → native .atto value-policy extractions (2026-11-04)

Five **new, executable guest-source policy implementations** have been authored
and registered as canonical `.atto` with **no automatic `.ores` compatibility
aliases**. They are stacked on Java PR #2553 and preserve earlier modules.

| .atto module | Java reference | Portable work | Host-only boundary |
| --- | --- | --- | --- |
| `cpu_budget_policy` | `CpuCarrierBudget.java` | clamp, cgroup quota ceil, pool size split | reads cgroup/OS CPU count, allocates carriers |
| `source_budget_policy` | `SourceAdmissionBudget.java` | closure byte/unit/depth arithmetic | verifies actual UTF-8 bytes and compiler import provenance |
| `cooperation_policy` | `SourceCooperationQuantum.java` | saturating backedge count, poll cadence, soft budget | clock and scheduler cooperation/suspension |
| `http_response_phase_policy` | `HttpResponseWirePhase.java` | started/sent/error/finish predicates | response monitor, atomic terminal state, physical wire |
| `admission_deadline_policy` | `GuestAdmissionDeadline.java` | five-minute bounds and elapsed-time predicates | monotonic clock, timer, context revocation, post-CAS fence |

This work is **not** an authority relocation. Java retains privileged host
enforcement; .atto values must not be used as sole proof of runner capacity,
authenticated imports, schedulable continuations, response state, or admission.

**Fixtures:** five `.atto` programs under
`src/test/resources/stdlib-parity/portable-java-policy-second-five/`.
`CanonicalAttoHostPolicySecondFiveTest` checks real compiler-owned guest
execution and Java compatibility reference observations; no source-shape test
may be counted as a compiled guest pass.

**Evidence:** AUTHORED only. CI has repeatedly failed before runner admission
(`runner_id=0`, `steps=[]`). Require exact-head executed Java/GraalVM tests,
negative compiler diagnostics, then LLVM/.NET/Wasm/Forge backends and
independent review before changing production enforcement or merging.

# Carrier Grade Linux (CGL): GraalVM pilot

> **CGL Level 2 evidence:** [timer-due-to-actor-user-start contract](CGL_DISPATCH_EVIDENCE_V1.md). A timer firing on schedule is *not* a user-code start deadline pass. The current helper is offline-only; actual actor instrumentation, target-host timing and schedulability proof remain outstanding.

Status: **draft Linux compatibility pilot**, NOT a CGL-qualified or hard-real-time deployment.

## Initial target

- Linux x86-64 or AArch64 carrier-grade distribution (including PREEMPT_RT systems).
- Java/GraalVM 25: JVM/Truffle and GraalVM native-aot launcher. Note that current native-aot packages the guest **interpreter**; it is not compiled Oreslang guest AOT.
- Run a non-privileged preflight on the target Linux host. For Native Image, validate the emitted ELF64 e_machine matches the current CPU. Then execute examples/together.ores through the existing launcher.
- Do not require root/RT privilege to launch a basic control-plane workload, and do not silently claim SCHED_FIFO/exclusive-core or hard realtime.
- Keep all private CI and test source within the original GitHub org until source-export approvals are resolved.

## Local steps

    rustc --edition=2021 -D warnings --test scripts/cgl_preflight.rs -o /tmp/cgl-tests
    /tmp/cgl-tests --test-threads=1
    rustc --edition=2021 -D warnings -O scripts/cgl_preflight.rs -o /tmp/cgl-preflight
    /tmp/cgl-preflight
    mvn -B -ntp verify
    mvn -B -ntp -DskipTests exec:java -Dexec.args="examples/together.ores"
    mvn -B -ntp -Pnative-aot -DskipTests package
    /tmp/cgl-preflight --binary target/ores-aot
    ./target/ores-aot --mode=aot --platform=linux examples/together.ores

Run these on an actual CGL target image before claiming compatibility. For a PREEMPT_RT-specific host, run with --require-preempt-rt and preserve the JSON report. Check libc/interpreter and dynamic dependency compatibility separately; the ELF header is not sufficient.

## Boundaries / forward work

- GraalVM actor scheduling remains *soft real time*, and @realtime is not proof of actual scheduler admission. Use explicit deadline-miss telemetry and tested policy; fail closed if a strictly bounded/hard actor profile is requested.
- Test cgroup cpusets, OS CPU affinity, CPU hotplug/SMT, IRQ load, monotonic timers, memory faults, affinity changes, and GC/JIT interference. Provide p50/p99/p99.9/max under network storms.
- Guarantee supervisor CONTROL progress during full mailboxes and faults; actor process isolation, brokered HTTP endpoint transfer, crash/restart recovery, and long soak remain independent work.
- Compare source/behavior with LLVM pilot on identical host images and source commit receipts. Neither backend is CGL-certified today.

Track Java issue #1061 for realtime annotation contract and docs/runtime/actor-physical-boundaries.md for physical isolation status.

## Audit and security gates (October 2026)

The hardened host preflight now requires a regular executable ELF64 little-endian
image, supported OS ABI, bounded and in-file program headers, at least one
PT_LOAD segment, host-matching e_machine and an existing PT_INTERP loader for
dynamic PIE. Symlinked files are rejected. This deliberately excludes
ET_DYN without PT_INTERP (including possible static PIE builds) from the
first pilot; extend only with dedicated source/ABI tests. No ELF-only check
proves that glibc versions, shared libraries or runtime syscalls are compatible.

PREEMPT_RT sysfs values are tri-state: only exact 1/0 count as known values;
missing or malformed values are unknown. A required RT proof fails closed.
That setting is not proof of deadline latency, privileged scheduling
admission, CPU exclusivity, or carrier-grade certification.

The negative test matrix covers foreign ELF architecture, malformed headers,
program-header bounds, missing executable segments and loader, bad mapping
permissions, symlinked executables, and unknown PREEMPT_RT state. The LLVM native C++ admission test also avoids
assert-dependent side effects, allowing it to fail even with NDEBUG enabled.

Private source must not be mirrored just because another organization has
available Actions minutes. The protected source Java allowlist
.github/private-ci-approved-repositories.txt is currently empty, and Java
security issue #962 prohibits exporting source until independent security
approval and complete provenance checks are in force. The suggested
sagitta-stack/sagitta-infra destination is awaiting approval in Java draft
PR #1326. No private-source Git blobs, trees, or branches were published to
another repository by this CGL audit.

Local validation is limited to the self-contained preflight/host probe.
Actual compiler execution on the exact PR Git tree, a real MontaVista/CGL
image, PREEMPT_RT deadlines, and >24h telecom fault-soak remain UNVERIFIED
until the named jobs record executed test steps on approved runners.

## Executable-mapping and stack admission (2026-10-29)

The ELF preflight rejects load segments carrying both PF_W and PF_X and any
PT_GNU_STACK segment with PF_X set. This is a narrow fail-closed *file-level*
security admission rule; it is **not** an enforcement of final process mappings,
JIT mappings, dynamic loader behavior, dependency flags, memory permissions,
or CGL real-time guarantees. The negative matrix includes writable/executable
PT_LOAD and executable-stack fixtures as well as admitted RX/normal-stack
controls. Inspect loaded shared libraries and actual `/proc/<pid>/maps` on
the target image separately before production admission.

The linked GitHub CI jobs must execute steps on the PR's exact head and record
source SHA/tree provenance. Zero-step GitHub failures remain runner admission
failures, not compiler test outcomes. Private-source mirroring remains blocked
until the source policy explicitly approves an immutable private destination
repository ID through independent security review.

## Direct-parser native ELF admission (2026-10-29 follow-up)

`inspect_elf` now rejects any `e_machine` outside the supported x86-64 and
AArch64 pilot even when called directly, without the top-level host preflight.
Every admitted ELF must also contain at least one loadable segment with the
execute bit set; data-only load maps cannot pass executable-image admission.
Both controls have negative tests and positive x86-64/AArch64 RX fixtures.

GraalVM's JVM compatibility workflow runs `mvn -B -ntp verify` before the
actual Oreslang guest-source execution; a `-DskipTests package` is not a JVM
regression pass. LLVM's workflow requires both named CTests independently.
All jobs bind checkout to the PR's immutable head SHA and report the Git tree.

The new checks were exercised with focused local synthetic ELF admission
cases and native GCC/Clang C executables. This does **not** mean the committed
full compiled Rust suite, Maven/GraalVM, LLVM compiler AOT, or carrier-grade
runtime test suites have executed. Zero-step GitHub Actions failures are
infrastructure/admission evidence only, not test failure evidence.

## Rust-only CGL admission tooling

The source-local host/ELF preflight and its adversarial regression suite are
implemented in `scripts/cgl_preflight.rs` using the Rust standard library.
Both CI jobs compile the command and run its native test harness; no additional
package manager or third-party dependencies are required for the preflight.
The output retains the `oreslang.cgl.compat.v1` JSON schema and exit code 2 for
admission failure. A successful Linux/ELF preflight is *not* realtime or vendor
CGL certification. Tests must execute with nonzero steps at the exact PR tree.
# Carrier Grade Linux (CGL): GraalVM pilot

Status: **draft Linux compatibility pilot**, NOT a CGL-qualified or hard-real-time deployment.

## Initial target

- Linux x86-64 or AArch64 carrier-grade distribution (including PREEMPT_RT systems).
- Java/GraalVM 25: JVM/Truffle and GraalVM native-aot launcher. Note that current native-aot packages the guest **interpreter**; it is not compiled Oreslang guest AOT.
- Run a non-privileged preflight on the target Linux host. For Native Image, validate the emitted ELF64 e_machine matches the current CPU. Then execute examples/together.ores through the existing launcher.
- Do not require root/RT privilege to launch a basic control-plane workload, and do not silently claim SCHED_FIFO/exclusive-core or hard realtime.
- Keep all private CI and test source within the original GitHub org until source-export approvals are resolved.

## Local steps

    rustc --edition=2021 -D warnings --test scripts/cgl_preflight.rs -o /tmp/cgl-tests
    /tmp/cgl-tests --test-threads=1
    rustc --edition=2021 -D warnings -O scripts/cgl_preflight.rs -o /tmp/cgl-preflight
    /tmp/cgl-preflight
    mvn -B -ntp verify
    mvn -B -ntp -DskipTests exec:java -Dexec.args="examples/together.ores"
    mvn -B -ntp -Pnative-aot -DskipTests package
    /tmp/cgl-preflight --binary target/ores-aot
    ./target/ores-aot --mode=aot --platform=linux examples/together.ores

Run these on an actual CGL target image before claiming compatibility. For a PREEMPT_RT-specific host, run with --require-preempt-rt and preserve the JSON report. Check libc/interpreter and dynamic dependency compatibility separately; the ELF header is not sufficient.

## Boundaries / forward work

- GraalVM actor scheduling remains *soft real time*, and @realtime is not proof of actual scheduler admission. Use explicit deadline-miss telemetry and tested policy; fail closed if a strictly bounded/hard actor profile is requested.
- Test cgroup cpusets, OS CPU affinity, CPU hotplug/SMT, IRQ load, monotonic timers, memory faults, affinity changes, and GC/JIT interference. Provide p50/p99/p99.9/max under network storms.
- Guarantee supervisor CONTROL progress during full mailboxes and faults; actor process isolation, brokered HTTP endpoint transfer, crash/restart recovery, and long soak remain independent work.
- Compare source/behavior with LLVM pilot on identical host images and source commit receipts. Neither backend is CGL-certified today.

Track Java issue #1061 for realtime annotation contract and docs/runtime/actor-physical-boundaries.md for physical isolation status.

## Audit and security gates (October 2026)

The hardened host preflight now requires a regular executable ELF64 little-endian
image, supported OS ABI, bounded and in-file program headers, at least one
PT_LOAD segment, host-matching e_machine and an existing PT_INTERP loader for
dynamic PIE. Symlinked files are rejected. This deliberately excludes
ET_DYN without PT_INTERP (including possible static PIE builds) from the
first pilot; extend only with dedicated source/ABI tests. No ELF-only check
proves that glibc versions, shared libraries or runtime syscalls are compatible.

PREEMPT_RT sysfs values are tri-state: only exact 1/0 count as known values;
missing or malformed values are unknown. A required RT proof fails closed.
That setting is not proof of deadline latency, privileged scheduling
admission, CPU exclusivity, or carrier-grade certification.

The negative test matrix covers foreign ELF architecture, malformed headers,
program-header bounds, missing executable segments and loader, bad mapping
permissions, symlinked executables, and unknown PREEMPT_RT state. The LLVM native C++ admission test also avoids
assert-dependent side effects, allowing it to fail even with NDEBUG enabled.

Private source must not be mirrored just because another organization has
available Actions minutes. The protected source Java allowlist
.github/private-ci-approved-repositories.txt is currently empty, and Java
security issue #962 prohibits exporting source until independent security
approval and complete provenance checks are in force. The suggested
sagitta-stack/sagitta-infra destination is awaiting approval in Java draft
PR #1326. No private-source Git blobs, trees, or branches were published to
another repository by this CGL audit.

Local validation is limited to the self-contained preflight/host probe.
Actual compiler execution on the exact PR Git tree, a real MontaVista/CGL
image, PREEMPT_RT deadlines, and >24h telecom fault-soak remain UNVERIFIED
until the named jobs record executed test steps on approved runners.

## Executable-mapping and stack admission (2026-10-29)

The ELF preflight rejects load segments carrying both PF_W and PF_X and any
PT_GNU_STACK segment with PF_X set. This is a narrow fail-closed *file-level*
security admission rule; it is **not** an enforcement of final process mappings,
JIT mappings, dynamic loader behavior, dependency flags, memory permissions,
or CGL real-time guarantees. The negative matrix includes writable/executable
PT_LOAD and executable-stack fixtures as well as admitted RX/normal-stack
controls. Inspect loaded shared libraries and actual `/proc/<pid>/maps` on
the target image separately before production admission.

The linked GitHub CI jobs must execute steps on the PR's exact head and record
source SHA/tree provenance. Zero-step GitHub failures remain runner admission
failures, not compiler test outcomes. Private-source mirroring remains blocked
until the source policy explicitly approves an immutable private destination
repository ID through independent security review.

## Direct-parser native ELF admission (2026-10-29 follow-up)

`inspect_elf` now rejects any `e_machine` outside the supported x86-64 and
AArch64 pilot even when called directly, without the top-level host preflight.
Every admitted ELF must also contain at least one loadable segment with the
execute bit set; data-only load maps cannot pass executable-image admission.
Both controls have negative tests and positive x86-64/AArch64 RX fixtures.

GraalVM's JVM compatibility workflow runs `mvn -B -ntp verify` before the
actual Oreslang guest-source execution; a `-DskipTests package` is not a JVM
regression pass. LLVM's workflow requires both named CTests independently.
All jobs bind checkout to the PR's immutable head SHA and report the Git tree.

The new checks were exercised with focused local synthetic ELF admission
cases and native GCC/Clang C executables. This does **not** mean the committed
full compiled Rust suite, Maven/GraalVM, LLVM compiler AOT, or carrier-grade
runtime test suites have executed. Zero-step GitHub Actions failures are
infrastructure/admission evidence only, not test failure evidence.

## Rust-only CGL admission tooling

The source-local host/ELF preflight and its adversarial regression suite are
implemented in `scripts/cgl_preflight.rs` using the Rust standard library.
Both CI jobs compile the command and run its native test harness; no additional
package manager or third-party dependencies are required for the preflight.
The output retains the `oreslang.cgl.compat.v1` JSON schema and exit code 2 for
admission failure. A successful Linux/ELF preflight is *not* realtime or vendor
CGL certification. Tests must execute with nonzero steps at the exact PR tree.

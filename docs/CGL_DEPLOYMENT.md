# Carrier Grade Linux (CGL): GraalVM pilot

Status: **draft Linux compatibility pilot**, NOT a CGL-qualified or hard-real-time deployment.

## Initial target

- Linux x86-64 or AArch64 carrier-grade distribution (including PREEMPT_RT systems).
- Java/GraalVM 25: JVM/Truffle and GraalVM native-aot launcher. Note that current native-aot packages the guest **interpreter**; it is not compiled Oreslang guest AOT.
- Run a non-privileged preflight on the target Linux host. For Native Image, validate the emitted ELF64 e_machine matches the current CPU. Then execute examples/together.ores through the existing launcher.
- Do not require root/RT privilege to launch a basic control-plane workload, and do not silently claim SCHED_FIFO/exclusive-core or hard realtime.
- Keep all private CI and test source within the original GitHub org until source-export approvals are resolved.

## Local steps

    python3 -m unittest discover -s tests -p test_cgl_preflight.py -v
    python3 scripts/cgl-preflight.py
    mvn -B -ntp -DskipTests package
    mvn -B -ntp -DskipTests exec:java -Dexec.args="examples/together.ores"
    mvn -B -ntp -Pnative-aot -DskipTests package
    python3 scripts/cgl-preflight.py --binary target/ores-aot
    ./target/ores-aot --mode=aot --platform=linux examples/together.ores

Run these on an actual CGL target image before claiming compatibility. For a PREEMPT_RT-specific host, run with --require-preempt-rt and preserve the JSON report. Check libc/interpreter and dynamic dependency compatibility separately; the ELF header is not sufficient.

## Boundaries / forward work

- GraalVM actor scheduling remains *soft real time*, and @realtime is not proof of actual scheduler admission. Use explicit deadline-miss telemetry and tested policy; fail closed if a strictly bounded/hard actor profile is requested.
- Test cgroup cpusets, OS CPU affinity, CPU hotplug/SMT, IRQ load, monotonic timers, memory faults, affinity changes, and GC/JIT interference. Provide p50/p99/p99.9/max under network storms.
- Guarantee supervisor CONTROL progress during full mailboxes and faults; actor process isolation, brokered HTTP endpoint transfer, crash/restart recovery, and long soak remain independent work.
- Compare source/behavior with LLVM pilot on identical host images and source commit receipts. Neither backend is CGL-certified today.

Track Java issue #1061 for realtime annotation contract and docs/runtime/actor-physical-boundaries.md for physical isolation status.

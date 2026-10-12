#!/usr/bin/env bash
# Exact-source Java 21 accelerator security smoke; not a substitute for Maven.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
javac -d "$work" \
    src/main/java/dev/oreslang/runtime/accel/QuantumCircuitIR.java \
    src/main/java/dev/oreslang/runtime/accel/GpuBufferFence.java \
    scripts/AcceleratorSafetySmoke.java
java -cp "$work" dev.oreslang.runtime.accel.AcceleratorSafetySmoke

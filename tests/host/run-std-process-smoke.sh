#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" \
  "$repo_root/src/main/java/dev/oreslang/runtime/TrustedChildProcessRunner.java" \
  "$repo_root/tests/host/TrustedChildProcessRunnerSmoke.java"
java -cp "$out" dev.oreslang.runtime.TrustedChildProcessRunnerSmoke

# Test built-in import aliases using only JDK and the exact registry source.
javac -d "$out" \
  "$repo_root/src/main/java/dev/oreslang/stdlib/CoreLibrarySources.java" \
  "$repo_root/tests/host/StdProcessNamespaceSmoke.java"
mkdir -p "$out/dev/oreslang/std"
for module in process.atto process_policy.atto trace.atto; do
  cp "$repo_root/stdlib/$module" "$out/dev/oreslang/std/$module"
done
java -cp "$out" dev.oreslang.stdlib.StdProcessNamespaceSmoke

# Compile the actual production NativeChildProcesses provider without Maven,
# inherited GitHub credentials, or a guest compiler. This is a real JDK process
# smoke, NOT proof that .atto lowering, Native Image or actor ownership works.
javac -d "$out" \
  "$repo_root/src/main/java/dev/oreslang/runtime/NativeChildProcesses.java" \
  "$repo_root/tests/host/NativeChildProcessesSmoke.java"
java -cp "$out" dev.oreslang.runtime.NativeChildProcessesSmoke

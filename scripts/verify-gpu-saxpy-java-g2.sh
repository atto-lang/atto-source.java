#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
head="$(git rev-parse HEAD)"
tree="$(git rev-parse HEAD^{tree})"
if [[ -n "${ATTO_EXPECTED_HEAD_SHA:-}" && "$head" != "$ATTO_EXPECTED_HEAD_SHA" ]]; then
  echo 'GPU_JAVA_G2_FAIL: expected immutable source HEAD mismatch' >&2
  exit 1
fi
for path in \
  'src/main/java/dev/atto/compiler/gpu/GpuSaxpySubsetCompiler.java' \
  'tests/gpu-saxpy-g2/GpuSaxpySubsetCompilerTest.java' \
  'tests/gpu-saxpy-g2/saxpy.atto' \
  'scripts/verify-gpu-saxpy-java-g2.sh'; do
  expected="$(git rev-parse "HEAD:$path")"
  observed="$(git hash-object "$path")"
  if [[ "$expected" != "$observed" ]]; then
    echo "GPU_JAVA_G2_FAIL: dirty/unpinned test file $path" >&2
    exit 1
  fi
  printf 'source_blob %s %s\n' "$path" "$observed"
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
printf 'source_head %s\nsource_tree %s\n' "$head" "$tree"
printf 'evidence_class RESTRICTED_JAVA_COMPILER_DEVICE_SOURCE_ONLY\nphysical_gpu_executed false\nproduction_guest_launch_executed false\n'
java -version 2>&1 | head -n1
javac -version
javac -encoding UTF-8 -d "$work" \
  src/main/java/dev/atto/compiler/gpu/GpuSaxpySubsetCompiler.java \
  tests/gpu-saxpy-g2/GpuSaxpySubsetCompilerTest.java
java -ea -cp "$work" GpuSaxpySubsetCompilerTest tests/gpu-saxpy-g2/saxpy.atto
java -cp "$work" dev.atto.compiler.gpu.GpuSaxpySubsetCompiler \
  tests/gpu-saxpy-g2/saxpy.atto "$work/kernel.cl"
expected_artifact='b5076980355531632de445e05e35554d3fb54ceeebaf707457863571bdeb77a4'
actual_artifact="$(sha256sum "$work/kernel.cl" | cut -d' ' -f1)"
if [[ "$actual_artifact" != "$expected_artifact" ]]; then
  echo 'GPU_JAVA_G2_FAIL: Java compiler output diverges from LLVM G2 golden' >&2
  exit 1
fi
clang -target spir64-unknown-unknown -x cl -cl-std=CL1.2 -S -emit-llvm \
  -o "$work/kernel.spir64.ll" "$work/kernel.cl"
grep -q 'target triple = "spir64-unknown-unknown"' "$work/kernel.spir64.ll"
grep -q 'spir_kernel void @attolang_saxpy_v1' "$work/kernel.spir64.ll"
printf 'artifact_sha256 %s\n' "$actual_artifact"
printf 'GPU_JAVA_G2_PASS: Java .atto device-source subset matches LLVM golden; NO physical GPU\n'

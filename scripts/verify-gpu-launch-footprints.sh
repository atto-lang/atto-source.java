#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

head="$(git rev-parse HEAD)"
tree="$(git rev-parse HEAD^{tree})"
expected="${GPU_EXPECTED_HEAD_SHA:-}"
if [[ -n "$expected" && "$head" != "$expected" ]]; then
  echo "GPU_HOST_PREFLIGHT_FAIL: checkout head mismatch" >&2
  exit 1
fi

source_path="src/main/java/dev/oreslang/compiler/GpuLaunchFootprints.java"
capacity_path="src/main/java/dev/oreslang/compiler/GpuKernelCapacityPreflightV1.java"
smoke_path="tests/gpu-launch-footprints/GpuLaunchFootprintsSmoke.java"
capacity_smoke_path="tests/gpu-launch-footprints/GpuKernelIntegratedCapacitySmoke.java"
for path in "$source_path" "$capacity_path" "$smoke_path" "$capacity_smoke_path"; do
  expected_blob="$(git rev-parse "HEAD:$path")"
  actual_blob="$(git hash-object "$path")"
  if [[ "$expected_blob" != "$actual_blob" ]]; then
    echo "GPU_HOST_PREFLIGHT_FAIL: modified source/test blob $path" >&2
    exit 1
  fi
  echo "verified_git_blob $path $actual_blob"
done

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

echo "evidence_class HOST_ONLY"
echo "guest_compiler_executed false"
echo "physical_gpu_executed false"
echo "source_head $head"
echo "source_tree $tree"
java -version 2>&1
javac -version
javac -encoding UTF-8 -d "$temp_dir" "$source_path" "$capacity_path" "$smoke_path" "$capacity_smoke_path"
java -ea -cp "$temp_dir" GpuLaunchFootprintsSmoke
java -ea -cp "$temp_dir" GpuKernelIntegratedCapacitySmoke
echo "GPU_HOST_PREFLIGHT_PASS: executed exact checkout host footprint and capacity oracles; NOT compiler guest or GPU execution"

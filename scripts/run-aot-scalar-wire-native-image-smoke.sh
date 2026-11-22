#!/usr/bin/env bash
# Linux/GraalVM only. Qualifies a HOST native-image codec, not an AttoLang guest binary.
set -euo pipefail
if ! command -v mvn >/dev/null 2>&1 || ! command -v native-image >/dev/null 2>&1; then
  echo 'AOT_SCALAR_WIRE_NATIVE_NOT_EXECUTED: Maven and GraalVM Native Image required' >&2
  exit 3
fi
head_sha="$(git rev-parse HEAD)"
tree_sha="$(git rev-parse HEAD^{tree})"
if [[ -n "${ATTO_EXPECTED_HEAD_SHA:-}" && "${head_sha}" != "${ATTO_EXPECTED_HEAD_SHA}" ]]; then
  echo 'AOT_SCALAR_WIRE_HEAD_MISMATCH' >&2
  exit 4
fi
if [[ "${#head_sha}" -ne 40 || "${#tree_sha}" -ne 40 ]]; then
  echo 'AOT_SCALAR_WIRE_INVALID_GIT_IDENTITY' >&2
  exit 5
fi
mvn -q -Dtest=AotScalarWireV1Test,AotScalarWireV1MatrixTest,AotScalarWireV1GoldenVectorsTest test
bash scripts/verify-aot-scalar-wire-host-linkage.sh target/classes
mvn -q dependency:build-classpath -Dmdep.outputFile=target/aot-scalar-wire-dependencies.txt
classpath="target/classes:target/test-classes:$(cat target/aot-scalar-wire-dependencies.txt)"
binary="target/aot-scalar-wire-host-native"
native-image --no-fallback -cp "${classpath}" \
  dev.oreslang.compiler.AotScalarWireV1NativeProbe -o "${binary}"
"${binary}"
if ! command -v sha256sum >/dev/null 2>&1; then
  echo 'AOT_SCALAR_WIRE_SHA256_TOOL_UNAVAILABLE' >&2
  exit 3
fi
printf 'HOST_NATIVE_AOT_SOURCE_HEAD=%s\n' "${head_sha}"
printf 'HOST_NATIVE_AOT_SOURCE_TREE=%s\n' "${tree_sha}"
printf 'HOST_NATIVE_AOT_BINARY_SHA256=%s\n' "$(sha256sum "${binary}" | awk '{print $1}')"
echo 'HOST_NATIVE_AOT_NO_FALLBACK_EXECUTED'
echo 'GUEST_NATIVE_AOT_NOT_EXECUTED'

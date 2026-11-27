#!/usr/bin/env bash
# Verify the GraalVM/Truffle component release, NOT the bundled JDK patch
# shown on the first line of native-image --version.
set -euo pipefail

pom="${1:-pom.xml}"
if [[ ! -f "$pom" ]]; then
  echo "native-image version gate: missing pom: $pom" >&2
  exit 2
fi
mapfile -t pinned < <(sed -nE 's/.*<graalvm.version>([^<]+)<\/graalvm.version>.*/\1/p' "$pom")
if [[ "${#pinned[@]}" -ne 1 || ! "${pinned[0]}" =~ ^[0-9]+(\.[0-9]+){2,}$ ]]; then
  echo "native-image version gate: expected exactly one numeric Graal/Truffle version in pom" >&2
  exit 2
fi
if ! command -v native-image >/dev/null 2>&1; then
  echo "native-image version gate: missing native-image executable" >&2
  exit 2
fi
if ! output="$(native-image --version)"; then
  echo "native-image version gate: native-image --version failed" >&2
  exit 2
fi

# Native Image from Oracle GraalVM 25.3.4.1 reports three distinct versions:
# native-image 25.0.4.1  (bundled JDK version)
# GraalVM Runtime Environment Oracle GraalVM 25.3.4.1+1.1 (...)
# Substrate VM Oracle GraalVM 25.3.4.1+1.1 (...)
# Both component releases must match the Maven dependency release exactly.
mapfile -t image_lines < <(grep -E '^native-image[[:space:]]' <<< "$output" || true)
mapfile -t runtime_lines < <(grep -E '^GraalVM Runtime Environment[[:space:]]' <<< "$output" || true)
mapfile -t substrate_lines < <(grep -E '^Substrate VM[[:space:]]' <<< "$output" || true)
if [[ "${#image_lines[@]}" -ne 1 || "${#runtime_lines[@]}" -ne 1 || "${#substrate_lines[@]}" -ne 1 ]]; then
  echo "native-image version gate: expected exactly one native-image, GraalVM Runtime, and Substrate VM version line" >&2
  exit 2
fi

if [[ ! "${image_lines[0]}" =~ ^native-image[[:space:]]+([0-9]+(\.[0-9]+)+)([[:space:]]|$) ]]; then
  echo "native-image version gate: malformed native-image version line" >&2
  exit 2
fi
jdk_version="${BASH_REMATCH[1]}"
pinned_major="${pinned[0]%%.*}"
if [[ "${jdk_version%%.*}" != "$pinned_major" ]]; then
  echo "native-image version gate: JDK major mismatch: pinned $pinned_major, native-image $jdk_version" >&2
  exit 1
fi

component_release() {
  local record="$1"
  if [[ "$record" =~ [[:space:]]GraalVM[[:space:]]+([0-9]+(\.[0-9]+){2,})([+][0-9.]+)?([[:space:]]|$) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    return 1
  fi
}
runtime_version="$(component_release "${runtime_lines[0]}")" || {
  echo "native-image version gate: GraalVM Runtime release missing or malformed" >&2
  exit 2
}
substrate_version="$(component_release "${substrate_lines[0]}")" || {
  echo "native-image version gate: Substrate VM release missing or malformed" >&2
  exit 2
}
if [[ "$runtime_version" != "${pinned[0]}" || "$substrate_version" != "${pinned[0]}" ]]; then
  echo "native-image version gate: mismatch: Maven Graal/Truffle ${pinned[0]}, Runtime $runtime_version, Substrate VM $substrate_version (bundled JDK $jdk_version)" >&2
  exit 1
fi
echo "native-image version gate: MATCH ${pinned[0]} (Runtime + Substrate VM; JDK $jdk_version)"

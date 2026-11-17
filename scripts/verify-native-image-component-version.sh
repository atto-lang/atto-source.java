#!/usr/bin/env bash
# Fail closed before Graal Native Image when compiler and Truffle artifacts differ.
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
output="$(native-image --version)"
actual="$(awk '$1 == "native-image" { print $2; exit }' <<< "$output")"
if [[ -z "$actual" || "$actual" != "${pinned[0]}" ]]; then
  echo "native-image version gate: mismatch: Maven Graal/Truffle ${pinned[0]}, Native Image ${actual:-unknown}" >&2
  echo "Install the matching GraalVM distribution before image generation." >&2
  exit 1
fi
echo "native-image version gate: MATCH ${pinned[0]}"

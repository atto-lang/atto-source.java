#!/usr/bin/env bash
# Dependency-free Java 21 ABI parity smoke; full Maven CI remains mandatory.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
command -v javac >/dev/null || { echo "JDK javac required" >&2; exit 2; }
command -v java >/dev/null || { echo "JDK java required" >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
javac -d "$work" src/main/java/dev/oreslang/compiler/TargetAbi.java scripts/TargetAbiSmoke.java
java -cp "$work" TargetAbiSmoke contracts/architecture/v1/targets.tsv

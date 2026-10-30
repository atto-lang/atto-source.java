#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
javac --release 21 -sourcepath src/main/java -d "$build_dir" verification/CollectionSharingProbe.java
java -cp "$build_dir" dev.oreslang.types.CollectionSharingProbe

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# Preserve exact checkout provenance in CI receipts. pull_request checkout
# commonly tests a synthetic merge commit; do not mislabel that as PR head.
if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  printf 'COLLECTION_SHARING_COMMIT=%s\n' "$(git rev-parse HEAD)"
  printf 'COLLECTION_SHARING_TREE=%s\n' "$(git rev-parse HEAD^{tree})"
  printf 'COLLECTION_SHARING_EVENT=%s\n' "${GITHUB_EVENT_NAME:-local}"
fi
javac -version
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
javac --release 21 -sourcepath src/main/java -d "$build_dir" verification/CollectionSharingProbe.java
java -cp "$build_dir" dev.oreslang.types.CollectionSharingProbe

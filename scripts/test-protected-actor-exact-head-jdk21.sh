#!/usr/bin/env bash
# Source-local, no-token, dependency-free Java 21 VM substrate check.
# This is NOT general AttoLang guest execution, Graal Native Image or zero-GC.
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

: "${EXPECTED_HEAD:?set EXPECTED_HEAD to the independently pinned 40-character PR head SHA}"
if [[ ! "$EXPECTED_HEAD" =~ ^[0-9a-f]{40}$ ]]; then
  echo "INVALID_EXPECTED_HEAD" >&2
  exit 2
fi
actual="$(git rev-parse HEAD)"
if [[ "$actual" != "$EXPECTED_HEAD" ]]; then
  echo "EXACT_HEAD_MISMATCH expected=$EXPECTED_HEAD actual=$actual" >&2
  exit 3
fi
if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  echo "SOURCE_DIRTY_OR_UNTRACKED" >&2
  exit 4
fi

command -v javac >/dev/null
command -v java >/dev/null
javac -version
java -version
tree="$(git rev-parse 'HEAD^{tree}')"
echo "EXACT_HEAD=$actual"
echo "EXACT_TREE=$tree"

classes="$(mktemp -d)"
trap 'rm -rf -- "$classes"' EXIT
javac --release 21 -Xlint:all -Werror -d "$classes" \
  src/main/java/dev/oreslang/runtime/StateContainerAccessGate.java \
  src/main/java/dev/oreslang/runtime/VirtualActorActivationRegistry.java \
  src/main/java/dev/oreslang/runtime/ScalarStateContainer.java \
  src/main/java/dev/oreslang/runtime/LocalVirtualActorI64.java \
  src/test/java/dev/oreslang/runtime/ProtectedActorStandaloneSmoke.java

java -ea -cp "$classes" dev.oreslang.runtime.ProtectedActorStandaloneSmoke
echo "VM_HOST_SUBSTRATE=PASS"
echo "PRODUCTION_GUEST_COMPILER=NOT_EXECUTED"
echo "GRAAL_NATIVE_IMAGE=NOT_EXECUTED"
echo "NATIVE_ARENA_RECLAMATION=NOT_IMPLEMENTED"
echo "FIVE_BACKEND_PARITY=NOT_EXECUTED"

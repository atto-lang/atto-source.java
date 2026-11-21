#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

for file in \
    src/main/java/dev/oreslang/types/Types.java \
    src/main/java/dev/oreslang/types/ThreadSafetyTraits.java \
    src/main/java/dev/oreslang/types/TaskAccessVerifier.java \
    src/test/java/dev/oreslang/types/TaskAccessVerifierSmoke.java; do
    [[ -f "$file" ]] || { echo "missing $file" >&2; exit 2; }
done

out="$(mktemp -d)"
trap 'rm -rf -- "$out"' EXIT
javac --release 21 -Xlint:all -Werror -d "$out" \
    src/main/java/dev/oreslang/types/Types.java \
    src/main/java/dev/oreslang/types/ThreadSafetyTraits.java \
    src/main/java/dev/oreslang/types/TaskAccessVerifier.java \
    src/test/java/dev/oreslang/types/TaskAccessVerifierSmoke.java
java -ea -cp "$out" dev.oreslang.types.TaskAccessVerifierSmoke

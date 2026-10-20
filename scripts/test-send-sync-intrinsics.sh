#!/usr/bin/env bash
# Small exact-source smoke with no Maven, Truffle, JUnit or network dependency.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    echo 'Java 21+ JDK is required' >&2
    exit 2
fi
for file in \
    src/main/java/dev/oreslang/types/Types.java \
    src/main/java/dev/oreslang/types/ThreadSafetyTraits.java \
    src/test/java/dev/oreslang/types/SendSyncIntrinsicSmoke.java; do
    if [[ ! -f "$file" ]]; then echo "Missing $file" >&2; exit 2; fi
done

out="$(mktemp -d)"
trap 'rm -rf -- "$out"' EXIT
javac --release 21 -Xlint:all -Werror -d "$out" \
    src/main/java/dev/oreslang/types/Types.java \
    src/main/java/dev/oreslang/types/ThreadSafetyTraits.java \
    src/test/java/dev/oreslang/types/SendSyncIntrinsicSmoke.java
java -ea -cp "$out" dev.oreslang.types.SendSyncIntrinsicSmoke

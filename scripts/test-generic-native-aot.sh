#!/usr/bin/env bash
# Exact-source Native Image generic conformance gates.
# Usage: scripts/test-generic-native-aot.sh /absolute/path/to/ores-aot
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo "usage: $0 /path/to/ores-aot" >&2
  exit 2
fi
binary="$(realpath "$1")"
test -x "$binary"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

"$binary" --build-info > "$scratch/build-info.json"
grep -Fq '"build_mode":"aot"' "$scratch/build-info.json"

cat > "$scratch/valid.ores" <<'ORES'
fnc choose<A, B=int, C=B, D=Array<C>, E=string>(A input): int {
    return 7;
}
pub routine main(): int {
    val int a = choose<int>(7);
    val int b = choose<A=int, D=Array<string>>(7);
    val int c = choose<E=bool, A=int>(7);
    return a + b + c - 21;
}
ORES
"$binary" --mode=aot --check "$scratch/valid.ores"
"$binary" --mode=aot "$scratch/valid.ores"
"$binary" --mode=aot --build-analysis --entry=main "$scratch/valid.ores" > "$scratch/analysis.out"
grep -Fq 'tree-shake retained:' "$scratch/analysis.out"
echo "PASS: native generic defaults, named overrides, guest main and reachability"

reject() {
  local fixture="$1"
  local expected="$2"
  if "$binary" --mode=aot --check "$scratch/$fixture.ores" > "$scratch/$fixture.out" 2>&1; then
    echo "FAIL: native compiler accepted invalid case $fixture" >&2
    cat "$scratch/$fixture.out" >&2
    exit 1
  fi
  if ! grep -Fq "$expected" "$scratch/$fixture.out"; then
    echo "FAIL: native compiler rejected $fixture for an unexpected reason" >&2
    cat "$scratch/$fixture.out" >&2
    exit 1
  fi
  echo "PASS: native rejects $fixture"
}
cat > "$scratch/nested-array.ores" <<'ORES'
fnc choose<T>(): int { return 7; }
pub routine main(): int { return choose<Array<_>>(); }
ORES
reject nested-array 'nested inference hole'

cat > "$scratch/nested-option.ores" <<'ORES'
fnc choose<T>(): int { return 7; }
pub routine main(): int { return choose<Option<Array<_>>>(); }
ORES
reject nested-option 'nested inference hole'

cat > "$scratch/named-nested.ores" <<'ORES'
fnc choose<T>(): int { return 7; }
pub routine main(): int { return choose<T=Result<int,_>>(); }
ORES
reject named-nested 'nested inference hole'

cat > "$scratch/declaration-hole.ores" <<'ORES'
fnc choose(Array<_> value): int { return 7; }
pub routine main(): int { return 7; }
ORES
reject declaration-hole 'inference hole'

cat > "$scratch/explicit-hole.ores" <<'ORES'
fnc choose<A, B=int>(A input): int { return 7; }
pub routine main(): int { return choose<int, _>(7); }
ORES
reject explicit-hole 'cannot infer generic parameter'

cat > "$scratch/duplicate-named.ores" <<'ORES'
fnc choose<A, B=int>(): int { return 7; }
pub routine main(): int { return choose<A=int, A=bool>(); }
ORES
reject duplicate-named 'duplicate generic argument'

cat > "$scratch/forward-default.ores" <<'ORES'
fnc choose<A=B, B=int>(): int { return 7; }
pub routine main(): int { return choose<int>(); }
ORES
reject forward-default 'forward or self-referential'

cat > "$scratch/unknown-default.ores" <<'ORES'
fnc choose<T=MissingNominal>(): int { return 7; }
pub routine main(): int { return choose<int>(); }
ORES
reject unknown-default 'unresolved default type'

cat > "$scratch/opaque-default.ores" <<'ORES'
fnc choose<T=Option<Any>>(): int { return 7; }
pub routine main(): int { return choose<int>(); }
ORES
reject opaque-default 'safe opaque/dynamic semantics'

echo 'PASS: all native generic admission and rejection cases'

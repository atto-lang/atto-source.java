#!/usr/bin/env bash
# Source-only AttoLang extension migration gate. Not guest/compiler evidence.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ci="${1:-$root/.github/workflows/ci.yml}"
fast="${2:-$root/.github/workflows/fast-ci.yml}"
pom="${3:-$root/pom.xml}"
core="${4:-$root/src/main/java/dev/oreslang/stdlib/CoreLibrarySources.java}"
die() { echo "AttoLang test migration admission: $*" >&2; exit 1; }

[[ -f "$ci" && -f "$fast" && -f "$pom" && -f "$core" ]] ||
  die "missing workflow or packaged library registry"
[[ -f "$root/stdlib/testing.atto" && ! -L "$root/stdlib/testing.atto" ]] ||
  die "canonical stdlib/testing.atto is absent or a symlink"
grep -Fq 'native AttoLang test suite failed' "$root/stdlib/testing.atto" ||
  die "canonical .atto standard library was replaced with legacy content"

# Source-only check for the canonical Atto CLI adapter. This does NOT execute
# the Rust runner or prove that Maven compiled a guest.
manifest="$root/atto-test.toml"
[[ -f "$manifest" && ! -L "$manifest" ]] ||
  die "Atto test adapter manifest missing or symlinked"
grep -Fqx 'files = ["examples/native-testing-regressions-40.atto"]' "$manifest" ||
  die "Atto test manifest no longer selects canonical 40-case guest"
grep -Fqx 'command = ["mvn", "-q", "-B", "-ntp", "-DskipTests", "compile", "exec:java", "-Dexec.args={source}"]' "$manifest" ||
  die "Atto test Maven adapter command changed without review"

# Only canonical .atto guests may be used by the Java CI harness. Existing
# .ores copies remain explicitly for transition and other backend fixtures.
case_sources=(
  examples/native-testing-harness-guards
  examples/native-testing-negative-empty
  examples/native-testing-negative-skipped
  examples/native-testing-negative-filtered
  examples/native-testing-negative-failed
  examples/native-testing-regressions-40
  examples/native-testing-runner-matrix
  examples/native-testing-semantics
  examples/native-testing
  tests/test_std_harness_regressions
  tests/test_std_harness_scalar_parity_batch4
  tests/test_std_harness_meta_batch4
  tests/test_std_harness_accounting_batch5
  tests/portable-scalar-v1-20261101
  tests/portable-scalar-v2-20261101
  tests/portable-scalar-v3-20261101
)
for stem in "${case_sources[@]}"; do
  source="$root/$stem.atto"
  [[ -f "$source" && ! -L "$source" ]] ||
    die "missing canonical guest or symlink: $stem.atto"
  grep -Fq 'ores.std/testing' "$source" ||
    die "canonical stdlib import missing: $stem.atto"
  if grep -Fq "from '../stdlib/testing'" "$source" ||
     grep -Fq 'from "../stdlib/testing"' "$source"; then
    die "guest still directly imports legacy relative stdlib: $stem.atto"
  fi
done

grep -Fq '<include>**/*.atto</include>' "$pom" ||
  die "Maven resources exclude .atto library files"
grep -Fq '"testing.atto"' "$core" ||
  die "compiler-owned stdlib registry excludes testing.atto"
grep -Fq 'body.equals("testing.atto")' "$core" ||
  die "canonical stdlib import does not select .atto"
grep -Fq 'body.equals("testing.ores")' "$core" ||
  die "legacy explicit suffix fallback was lost"

# Legacy multi-backend scalar manifest is preserved, while six canonical
# .atto twins must be byte-identical to the independently reviewed blobs.
scalar_dir="$root/contracts/parity/native-scalar-zero-v1"
scalar_manifest="$scalar_dir/cases.tsv"
[[ -f "$scalar_manifest" ]] || die "missing reviewed scalar manifest"
[[ "$(git hash-object "$scalar_manifest")" == "a87f64af3e448ba10927eb2932900679896b283b" ]] ||
  die "reviewed scalar manifest changed"
scalar_count=0
while IFS=$'\t' read -r filename expected pinned; do
  [[ -z "$filename" || "$filename" == \#* ]] && continue
  [[ "$filename" == *.ores && "$pinned" =~ ^[0-9a-f]{40}$ ]] ||
    die "invalid scalar fixture identity"
  canonical="$scalar_dir/${filename%.ores}.atto"
  [[ -f "$canonical" && ! -L "$canonical" ]] ||
    die "missing .atto scalar source: $filename"
  [[ "$(git hash-object "$canonical")" == "$pinned" ]] ||
    die "canonical .atto source differs from reviewed blob: $filename"
  scalar_count=$((scalar_count + 1))
done < "$scalar_manifest"
[[ "$scalar_count" -eq 6 ]] || die "expected exactly six reviewed .atto scalar guests"

for workflow in "$ci" "$fast"; do
  if grep -Eq '(examples/native-testing|tests/test_std_harness_|tests/portable-scalar-v[123]-20261101)[^[:space:]]*\.ores' "$workflow"; then
    die "CI still executes a legacy .ores harness guest"
  fi
  grep -Fq 'examples/native-testing-regressions-40.atto' "$workflow" ||
    die "missing canonical 40-case guest invocation"
  grep -Fq 'tests/test_std_harness_regressions.atto' "$workflow" ||
    die "missing canonical native core guest invocation"
done
grep -Fq 'examples/native-testing-negative-${variant}.atto' "$ci" ||
  die "negative guest matrix must use canonical .atto sources"
grep -Fq 'native AttoLang test suite failed' "$ci" ||
  die "negative oracle must identify canonical .atto runtime"

# Source-free adversarial changes must be rejected by the gate itself.
if [[ "$#" -eq 0 ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  cp "$fast" "$tmp/fast.yml"
  sed -i 's#examples/native-testing-regressions-40.atto#examples/native-testing-regressions-40.ores#' "$tmp/fast.yml"
  if bash "$0" "$ci" "$tmp/fast.yml" "$pom" "$core" >/dev/null 2>&1; then
    die "admitted a legacy source CI downgrade"
  fi
  cp "$pom" "$tmp/pom.xml"
  sed -i 's#<include>\*\*/\*.atto</include>##g' "$tmp/pom.xml"
  if bash "$0" "$ci" "$fast" "$tmp/pom.xml" "$core" >/dev/null 2>&1; then
    die "admitted a packaging regression"
  fi
  cp "$core" "$tmp/CoreLibrarySources.java"
  sed -i 's#body.equals("testing.atto")#body.equals("testing.disabled")#' "$tmp/CoreLibrarySources.java"
  if bash "$0" "$ci" "$fast" "$pom" "$tmp/CoreLibrarySources.java" >/dev/null 2>&1; then
    die "admitted removal of .atto canonical import"
  fi
fi
echo "PASS: AttoLang .atto source, import, resource and CI downgrade admission (source-only)"

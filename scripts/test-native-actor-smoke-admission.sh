#!/usr/bin/env bash
# Source-free adversarial verifier tests: mock CLI, NOT Oreslang guest execution.
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$root"
guard="$root/scripts/verify-native-actor-smoke.sh"
scratch="$(mktemp -d)"
trap 'rm -rf -- "$scratch"' EXIT
mock="$scratch/mock-ores"
guest="$scratch/valid-guest.ores"
printf 'pub routine main(): void { return; }\n' > "$guest"
blob="$(git hash-object "$guest")"

cat > "$mock" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case_name="$MOCK_CASE"
if [[ "$1" == --check ]]; then
  file="$2"
  if [[ "$file" == */valid.ores ]]; then
    if [[ "$case_name" == valid-io-error ]]; then
      echo 'unable to read source' >&2
      exit 1
    fi
    exit 0
  fi
  [[ "$file" == */invalid.ores ]] || exit 2
  if [[ "$case_name" == invalid-accepted ]]; then exit 0; fi
  if [[ "$case_name" == invalid-timeout ]]; then exit 124; fi
  if [[ "$case_name" == invalid-signal ]]; then exit 137; fi
  if [[ "$case_name" == invalid-usage ]]; then exit 2; fi
  if [[ "$case_name" == invalid-empty ]]; then exit 1; fi
  if [[ "$case_name" == invalid-other-error ]]; then
    echo 'cannot open source path' >&2
    exit 1
  fi
  if [[ "$case_name" == invalid-unpositioned ]]; then
    echo "$file: error: expected expression" >&2
    exit 1
  fi
  if [[ "$case_name" == invalid-stdout ]]; then
    echo 'unexpected output on stdout'
  fi
  echo "$file:2:10: error: expected expression" >&2
  exit 1
fi
if [[ "$case_name" == guest-failed ]]; then exit 1; fi
if [[ "$case_name" == guest-stderr ]]; then echo 'unexpected warning' >&2; fi
if [[ "$case_name" == guest-mismatch ]]; then
  printf 'not-complete'
else
  printf complete
fi
MOCK
chmod 0700 "$mock"
compiler_sha256="$(sha256sum -- "$mock" | cut -d ' ' -f 1)"

MOCK_CASE=valid bash "$guard" "$mock" "$compiler_sha256" "$guest" "$blob" complete \
  > "$scratch/good.stdout" 2> "$scratch/good.stderr" || {
    echo 'ADMISSION_TEST_FAILED: valid positive mock was rejected' >&2
    cat "$scratch/good.stderr" >&2
    exit 1
  }
grep -q '^NATIVE_AOT_ACTOR_GUEST_SMOKE_PASS=1' "$scratch/good.stdout"
rejected=0
for scenario in invalid-accepted invalid-timeout invalid-signal invalid-usage \
  invalid-empty invalid-other-error invalid-unpositioned invalid-stdout \
  valid-io-error guest-failed guest-stderr guest-mismatch; do
  if MOCK_CASE="$scenario" bash "$guard" "$mock" "$compiler_sha256" "$guest" "$blob" complete \
    > "$scratch/out" 2> "$scratch/err"; then
    echo "ADMISSION_TEST_FAILED: false positive: $scenario" >&2
    exit 1
  fi
  grep -q '^NATIVE_ACTOR_SMOKE_REJECTED:' "$scratch/err" || {
    echo "ADMISSION_TEST_FAILED: unexpected error on $scenario" >&2
    cat "$scratch/err" >&2
    exit 1
  }
  rejected=$((rejected+1))
done
if MOCK_CASE=valid bash "$guard" "$mock" "$compiler_sha256" "$guest" \
  0000000000000000000000000000000000000000 complete > "$scratch/out" 2>"$scratch/err"; then
  echo 'ADMISSION_TEST_FAILED: accepted wrong guest blob' >&2
  exit 1
fi
rejected=$((rejected+1))
ln -s "$guest" "$scratch/link.ores"
if MOCK_CASE=valid bash "$guard" "$mock" "$compiler_sha256" "$scratch/link.ores" "$blob" complete > "$scratch/out" 2>"$scratch/err"; then
  echo 'ADMISSION_TEST_FAILED: accepted symlinked guest' >&2
  exit 1
fi
rejected=$((rejected+1))
# The compiler binary must be bound to the sha256 build receipt; neither a
# syntactically valid wrong digest nor an altered binary may be admitted.
if MOCK_CASE=valid bash "$guard" "$mock" \
  0000000000000000000000000000000000000000000000000000000000000000 \
  "$guest" "$blob" complete > "$scratch/out" 2>"$scratch/err"; then
  echo 'ADMISSION_TEST_FAILED: accepted wrong compiler digest' >&2
  exit 1
fi
rejected=$((rejected+1))
printf '\n# modified binary after pin\n' >> "$mock"
if MOCK_CASE=valid bash "$guard" "$mock" "$compiler_sha256" "$guest" "$blob" complete \
  > "$scratch/out" 2>"$scratch/err"; then
  echo 'ADMISSION_TEST_FAILED: accepted modified compiler binary' >&2
  exit 1
fi
rejected=$((rejected+1))
[[ "$rejected" -eq 16 ]] || exit 1
printf 'NATIVE_ACTOR_SMOKE_ADVERSARIAL_PASS positive=1 negative=%s (mock only)\n' "$rejected"

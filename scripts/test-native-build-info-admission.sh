#!/usr/bin/env bash
# This is source-free jq admission testing, NOT compiler/guest execution.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
guard="$root/scripts/verify-native-build-info.sh"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
case_file="$scratch/build-info.json"
passed=0
rejected=0

accept() {
  local label="$1"
  bash "$guard" "$case_file" > "$scratch/verdict" || {
    echo "METADATA_TEST_FAILED: positive: $label" >&2
    exit 1
  }
  grep -qx 'NATIVE_BUILD_INFO_ADMISSION_PASS schema=aot-v1' "$scratch/verdict"
  passed=$((passed+1))
}
reject() {
  local label="$1"
  if bash "$guard" "$case_file" > "$scratch/verdict" 2>"$scratch/diagnostic"; then
    echo "METADATA_TEST_FAILED: accepted invalid case: $label" >&2
    exit 1
  fi
  grep -q '^NATIVE_BUILD_INFO_REJECTED:' "$scratch/diagnostic"
  rejected=$((rejected+1))
}

cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
accept valid_native_aot

# Last-value-wins would allow these without checking the ORIGINAL key stream.
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":false,"guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject duplicate_false_then_true
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject duplicate_true_then_true
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_\u0065xecution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject duplicate_unicode_alias
cat > "$case_file" <<'JSON'
{"build_mode":"jvm","build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject duplicate_mode_override

cat > "$case_file" <<'JSON'
{"build_mode":"jvm","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject jvm_mode
cat > "$case_file" <<'JSON'
{"build_mode":"hybrid","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject hybrid_mode
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":"true","guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject string_guest_capability
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":false,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject false_guest_capability
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject missing_guest_capability
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"runtime-selected","managed_reference_mode":"compressed32"}
JSON
reject wrong_aot_mode
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit"}
JSON
reject missing_managed_reference_mode
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject empty_execution_mode
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32","unknown":true}
JSON
reject unexpected_extra_field
cat > "$case_file" <<'JSON'
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
{"build_mode":"aot","default_execution_mode":"interpreter","guest_execution":true,"guest_execution_mode":"interpreter-no-jit","managed_reference_mode":"compressed32"}
JSON
reject multiple_json_documents
printf '%s\n' '{"build_mode":"aot","guest_execution":' > "$case_file"
reject malformed_json
: > "$case_file"
reject empty_file
printf '%2049s\n' 'x' > "$case_file"
reject oversized_json

[[ "$passed" -eq 1 && "$rejected" -eq 17 ]] || {
  echo "METADATA_TEST_FAILED: counts positive=$passed negative=$rejected" >&2
  exit 1
}
printf 'NATIVE_BUILD_INFO_ADVERSARIAL_PASS positive=%s negative=%s\n' "$passed" "$rejected"

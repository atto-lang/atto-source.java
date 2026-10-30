#!/usr/bin/env bash
# Strict source-free validator for the Oreslang native AOT CLI's --build-info.
# Only a genuine CLI process can supply production evidence; this helper
# validates its output, not the existence or authenticity of the binary.
set -euo pipefail

fail() {
  printf 'NATIVE_BUILD_INFO_REJECTED: %s\n' "$1" >&2
  exit 1
}
[[ $# -eq 1 ]] || fail 'usage: verify-native-build-info.sh path'
file="$1"
[[ -f "$file" && ! -L "$file" && -s "$file" ]] || fail 'missing, empty or symlinked JSON'
[[ "$(wc -c < "$file")" -le 2048 ]] || fail 'oversized JSON'
command -v jq >/dev/null 2>&1 || fail 'jq is required'

# jq normally resolves repeated keys by last-value-wins. This parsed object
# check establishes the exact schema, value types and single-document shape,
# but MUST be combined with the original token-stream duplicate check below.
jq -e -s '
  length == 1 and (.[0] |
    type == "object"
    and (keys == ["build_mode", "default_execution_mode",
                  "guest_execution", "guest_execution_mode",
                  "managed_reference_mode"])
    and .build_mode == "aot"
    and .guest_execution == true
    and .guest_execution_mode == "interpreter-no-jit"
    and (.default_execution_mode | type == "string" and length > 0)
    and (.managed_reference_mode | type == "string" and length > 0))
' "$file" >/dev/null || fail 'invalid AOT metadata shape or values'

# Produce exactly ONE result. jq --stream -s can emit a false result followed
# by a true result for duplicate keys; jq -e would then exit 0 (a false PASS).
# Decode original key tokens so \u escape aliases are treated as duplicates.
jq -n --stream -e '
  reduce inputs as $event
    ({seen: [], duplicate: false};
     if ($event | length) == 2 and ($event[0] | length) == 1 then
       if (.seen | index($event[0][0])) != null then
         .duplicate = true
       else
         .seen += [$event[0][0]]
       end
     else . end)
  | (.duplicate | not)
' "$file" >/dev/null || fail 'duplicate JSON key, including escaped alias'

echo 'NATIVE_BUILD_INFO_ADMISSION_PASS schema=aot-v1'

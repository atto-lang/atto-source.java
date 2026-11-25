#!/usr/bin/env bash
# Host-only tests for retry classification and attempt bounds.
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/mvn" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
n="$(cat "$COUNT")"; n=$((n + 1)); echo "$n" > "$COUNT"
case "$MODE" in
  recover) if (( n > 1 )); then test "$1" = -U; exit 0; fi ;;
  compile) echo '[ERROR] COMPILATION ERROR';;
  test) echo '[INFO] Tests run: 1, Failures: 1';;
  other) echo '[ERROR] HTTP Status: 403'; exit 17;;
  plain) echo '[ERROR] HTTP Status: 429'; exit 17;;
esac
echo '[ERROR] PluginResolutionException HTTP Status: 429'
exit 17
SH
printf '#!/usr/bin/env bash\nexit 0\n' > "$tmp/sleep"
chmod +x "$tmp/mvn" "$tmp/sleep"
export PATH="$tmp:$PATH" COUNT="$tmp/count"
for mode in recover always compile test other plain; do
    export MODE="$mode"; echo 0 > "$COUNT"
    rc=0
    bash "$here/maven-retry.sh" -B test > "$tmp/output" 2>&1 || rc=$?
    case "$mode" in
      recover) expected=2; status=0;;
      always) expected=3; status=17;;
      *) expected=1; status=17;;
    esac
    test "$(cat "$COUNT")" = "$expected"
    test "$rc" = "$status"
    printf 'PASS retry classification: %s (%s attempts, exit %s)\n' "$mode" "$expected" "$status"
done

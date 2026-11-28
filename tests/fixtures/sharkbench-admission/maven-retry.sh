#!/usr/bin/env bash
# Retry only a pre-test Maven dependency/plugin resolution HTTP 429.
set -euo pipefail
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
for attempt in 1 2 3; do
    args=("$@")
    if (( attempt > 1 )); then args=(-U "${args[@]}"); fi
    set +e
    mvn "${args[@]}" 2>&1 | tee "$log"
    statuses=("${PIPESTATUS[@]}")
    set -e
    if (( statuses[1] != 0 )); then exit "${statuses[1]}"; fi
    rc="${statuses[0]}"
    if (( rc == 0 )); then exit 0; fi
    if (( attempt == 3 )) ||
        ! grep -Eq 'PluginResolutionException|DependencyResolutionException' "$log" ||
        ! grep -Eq 'HTTP Status: 429|status code: 429|status code 429' "$log" ||
        grep -Eq 'Tests run:|\[INFO\] Running |COMPILATION ERROR|There are test failures' "$log"; then
        exit "$rc"
    fi
    delay=$((attempt * 15))
    printf 'RETRY: pre-test Maven dependency HTTP 429; attempt %s/3 after %ss\n' "$((attempt + 1))" "$delay"
    sleep "$delay"
done
exit 1

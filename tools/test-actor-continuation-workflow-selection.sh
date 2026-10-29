#!/usr/bin/env bash
# Source-free guard: ensure the actor/GraalVM acceptance job really invokes
# both ledger tests. No checkout, credentials or JDK required to self-test.
set -euo pipefail

mode="${1:---self-test}"
workflow="${2:-.github/workflows/actor-exclusive-carrier-proof.yml}"
fail() { printf 'ACTOR_CI_SELECTION_REJECTED: %s\n' "$1" >&2; exit 64; }
check() {
    local file="$1" job selection test_name
    [[ -f "$file" && ! -L "$file" ]] || fail 'missing or symlinked workflow'
    job="$(awk '
        /^  actor-runtime-exclusivity:[[:space:]]*$/ { inside=1; next }
        inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { exit }
        inside { print }
    ' "$file")"
    [[ -n "$job" ]] || fail 'actor-runtime-exclusivity job missing'
    grep -Fq 'graalvm/setup-graalvm@' <<< "$job" || fail 'GraalVM setup absent'
    grep -Eq "^[[:space:]]*java-version: ['\"]?25['\"]?[[:space:]]*$" <<< "$job" || fail 'JDK25 absent'
    selection="$(sed -n '/^[[:space:]]*-Dtest=/p' <<< "$job")"
    [[ $(wc -l <<< "$selection") == 1 ]] || fail 'exactly one Maven test selector required'
    selection="${selection#*-Dtest=}"
    grep -Fq -- '-Dores.runtime.carriers=native' <<< "$job" || fail 'native carrier missing'
    for test_name in \
        ActorDispatchIdentityTest \
        ActorMailboxExecutionBoundaryTest \
        ActorOffCarrierTurnExecutorTest \
        VersionedActorCellTest \
        ActorContinuationLedgerTest \
        ActorContinuationLedgerIntegrationTest; do
        [[ ",$selection," == *",$test_name,"* ]] || fail "missing active Maven selector $test_name"
    done
    grep -Eq -- '^[[:space:]]*-DfailIfNoTests=true test[[:space:]]*$' <<< "$job" \
        || fail 'failIfNoTests=true missing'
}
case "$mode" in
    --check)
        check "$workflow"
        echo ACTOR_CI_SELECTION_PASS
        ;;
    --self-test)
        check "$workflow"
        work="$(mktemp -d)"
        trap 'rm -rf "$work"' EXIT
        sed 's/,ActorContinuationLedgerIntegrationTest//' "$workflow" > "$work/dropped-integration.yml"
        if bash "$0" --check "$work/dropped-integration.yml" >/dev/null 2>&1; then
            fail 'negative fixture dropping integration test was accepted'
        fi
        sed 's/,ActorContinuationLedgerTest//' "$workflow" > "$work/dropped-ledger.yml"
        if bash "$0" --check "$work/dropped-ledger.yml" >/dev/null 2>&1; then
            fail 'negative fixture dropping ledger test was accepted'
        fi
        sed "s/java-version: '25'/java-version: '21'/" "$workflow" > "$work/jdk21.yml"
        if bash "$0" --check "$work/jdk21.yml" >/dev/null 2>&1; then
            fail 'negative fixture downgrading JDK was accepted'
        fi
        sed 's/-DfailIfNoTests=true test/test/' "$workflow" > "$work/false-green.yml"
        if bash "$0" --check "$work/false-green.yml" >/dev/null 2>&1; then
            fail 'negative fixture disabling failIfNoTests was accepted'
        fi
        sed "s/^  actor-runtime-exclusivity:/  renamed-away-acceptance-job:/' "$workflow" > "$work/removed-job.yml"
        if bash "$0" --check "$work/removed-job.yml" >/dev/null 2>&1; then
            fail 'negative fixture dropping acceptance job was accepted'
        fi
        printf 'ACTOR_CI_SELECTION_SELFTEST_PASS: 1 positive and 5 negative cases\n'
        ;;
    *) fail 'usage: script --check|--self-test [path to actor workflow]' ;;
esac

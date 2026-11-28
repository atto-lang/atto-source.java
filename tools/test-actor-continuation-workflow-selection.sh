#!/usr/bin/env bash
# Source-free guard: ensure the actor/GraalVM acceptance job really invokes
# both ledger tests. No checkout, credentials or JDK required to self-test.
set -euo pipefail

mode="${1:---self-test}"
workflow="${2:-.github/workflows/actor-exclusive-carrier-proof.yml}"
fail() { printf 'ACTOR_CI_SELECTION_REJECTED: %s\n' "$1" >&2; exit 64; }
check() {
    local file="$1" job test_step command_lines selection test_name
    [[ -f "$file" && ! -L "$file" ]] || fail 'missing or symlinked workflow'
    job="$(awk '
        /^  actor-runtime-exclusivity:[[:space:]]*$/ { inside=1; next }
        inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { exit }
        inside { print }
    ' "$file")"
    [[ -n "$job" ]] || fail 'actor-runtime-exclusivity job missing'
    # A skipped or allowed-to-fail acceptance step is not a test receipt.
    # Fail closed on conditional/soft-fail actor steps rather than claiming a
    # green job when Maven never executes or its failure is swallowed.
    if grep -Eq '^[[:space:]]*(if|continue-on-error|env|defaults|shell|working-directory):' <<< "$job"; then
        fail 'conditional, environment-modified or continue-on-error acceptance steps are forbidden'
    fi
    grep -Fq 'graalvm/setup-graalvm@' <<< "$job" || fail 'GraalVM setup absent'
    grep -Eq "^[[:space:]]*java-version: ['\"]?25['\"]?[[:space:]]*$" <<< "$job" || fail 'JDK25 absent'
    # Only the actual Maven execution step may authorize test selectors;
    # an env value, comment, or earlier setup step is not acceptance.
    test_step="$(awk '
        /^      - name: Compile and run actual actor execution boundary tests[[:space:]]*$/ { inside=1; next }
        inside && /^      - (name|uses):/ { exit }
        inside { print }
    ' <<< "$job")"
    [[ -n "$test_step" ]] || fail 'actor Maven execution step missing'
    grep -Eq '^[[:space:]]*run: >[[:space:]]*$' <<< "$test_step" \
        || fail 'actor Maven command must be an active folded run step'
    grep -Eq '^[[:space:]]*mvn -B -ntp -Dores.runtime.carriers=native[[:space:]]*$' \
        <<< "$test_step" || fail 'native Maven invocation missing or not active'
    if grep -Eq -- '-DskipTests|-Dmaven.test.skip|[|][|][[:space:]]*true' \
        <<< "$test_step"; then
        fail 'actor acceptance Maven command may not bypass test failures'
    fi
    # GitHub folds this YAML scalar into one shell command. Allow exactly
    # three literal command lines, not a later bypass, injected env, wrapper,
    # or an additional shell command that masks the Maven exit status.
    command_lines="$(sed -n '/^[[:space:]]*run: >[[:space:]]*$/,$p' <<< "$test_step" | sed '1d; /^[[:space:]]*$/d')"
    [[ $(wc -l <<< "$command_lines") == 3 ]] \
        || fail 'actor Maven acceptance command must have exactly three lines'
    selection="$(sed -n '/^[[:space:]]*-Dtest=/p' <<< "$test_step")"
    [[ $(wc -l <<< "$selection") == 1 ]] || fail 'exactly one Maven test selector required'
    selection="${selection#*-Dtest=}"
    grep -Fq -- '-Dores.runtime.carriers=native' <<< "$test_step" || fail 'native carrier missing'
    for test_name in \
        ActorDispatchIdentityTest \
        ActorMailboxExecutionBoundaryTest \
        ActorOffCarrierTurnExecutorTest \
        VersionedActorCellTest \
        ActorContinuationLedgerTest \
        ActorContinuationLedgerIntegrationTest \
        ActorSupervisorMailChannelBoundaryTest; do
        [[ ",$selection," == *",$test_name,"* ]] || fail "missing active Maven selector $test_name"
    done
    grep -Eq -- '^[[:space:]]*-DfailIfNoTests=true test[[:space:]]*$' <<< "$test_step" \
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
        sed 's/,ActorSupervisorMailChannelBoundaryTest//' "$workflow" > "$work/dropped-supervisor-mail-channel.yml"
        if bash "$0" --check "$work/dropped-supervisor-mail-channel.yml" >/dev/null 2>&1; then
            fail 'negative fixture dropping supervisor mail/channel gate was accepted'
        fi
        sed "s/java-version: '25'/java-version: '21'/" "$workflow" > "$work/jdk21.yml"
        if bash "$0" --check "$work/jdk21.yml" >/dev/null 2>&1; then
            fail 'negative fixture downgrading JDK was accepted'
        fi
        sed 's/-DfailIfNoTests=true test/test/' "$workflow" > "$work/false-green.yml"
        if bash "$0" --check "$work/false-green.yml" >/dev/null 2>&1; then
            fail 'negative fixture disabling failIfNoTests was accepted'
        fi
        sed 's/^  actor-runtime-exclusivity:/  renamed-away-acceptance-job:/' "$workflow" > "$work/removed-job.yml"
        if bash "$0" --check "$work/removed-job.yml" >/dev/null 2>&1; then
            fail 'negative fixture dropping acceptance job was accepted'
        fi
        sed '/^      - name: Compile and run actual actor execution boundary tests/a\        continue-on-error: true' "$workflow" > "$work/soft-fail.yml"
        if bash "$0" --check "$work/soft-fail.yml" >/dev/null 2>&1; then
            fail 'negative fixture allowing Maven failure was accepted'
        fi
        sed '/^      - name: Compile and run actual actor execution boundary tests/a\        if: false' "$workflow" > "$work/skipped-maven.yml"
        if bash "$0" --check "$work/skipped-maven.yml" >/dev/null 2>&1; then
            fail 'negative fixture skipping Maven was accepted'
        fi
        sed 's/-Dores.runtime.carriers=native/-Dores.runtime.carriers=native -DskipTests/' "$workflow" > "$work/skipped-tests.yml"
        if bash "$0" --check "$work/skipped-tests.yml" >/dev/null 2>&1; then
            fail 'negative fixture disabling JUnit was accepted'
        fi
        sed 's/          mvn -B -ntp/          echo mvn -B -ntp/' "$workflow" > "$work/fake-maven.yml"
        if bash "$0" --check "$work/fake-maven.yml" >/dev/null 2>&1; then
            fail 'negative fixture pretending to run Maven was accepted'
        fi
        sed '/^          -DfailIfNoTests=true test$/a\          || :' "$workflow" > "$work/masked-maven.yml"
        if bash "$0" --check "$work/masked-maven.yml" >/dev/null 2>&1; then
            fail 'negative fixture masking Maven failures was accepted'
        fi
        sed '/^          -DfailIfNoTests=true test$/a\          echo BUILD_SUCCESS' "$workflow" > "$work/extra-shell.yml"
        if bash "$0" --check "$work/extra-shell.yml" >/dev/null 2>&1; then
            fail 'negative fixture injecting an extra command was accepted'
        fi
        sed '/^      - name: Compile and run actual actor execution boundary tests/a\        env:\n          BASH_ENV: /tmp/unreviewed-hook' "$workflow" > "$work/env-injection.yml"
        if bash "$0" --check "$work/env-injection.yml" >/dev/null 2>&1; then
            fail 'negative fixture injecting an ambient shell hook was accepted'
        fi
        printf 'ACTOR_CI_SELECTION_SELFTEST_PASS: 1 positive and 13 negative cases\n'
        ;;
    *) fail 'usage: script --check|--self-test [path to actor workflow]' ;;
esac

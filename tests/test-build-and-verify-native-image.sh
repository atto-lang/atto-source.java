#!/bin/sh
# Source-free contract tests. This tiny mock ELF tests the gate's decisions,
# NOT GraalVM Native Image compilation or the Oreslang runtime.
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
script="$repo_root/scripts/build-and-verify-native-image.sh"
test -f "$script" || { echo "missing native-image gate" >&2; exit 2; }
command -v cc >/dev/null 2>&1 || { echo "C compiler required" >&2; exit 2; }
command -v file >/dev/null 2>&1 || { echo "file utility required" >&2; exit 2; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir -p "$tmp/bin" "$tmp/graal/bin" "$tmp/work/examples"
for name in callables together native-core-library native-testing native-channel-select-smoke native-future-select-smoke strict-root-await shared-private-actor-isolation private-actor-sharing-invalid; do
  echo '// placeholder: not Oreslang source' > "$tmp/work/examples/$name.ores"
done

# Compile a native fixture with programmable behavior to exercise validation.
cat > "$tmp/mock.c" <<'C_SOURCE'
#define _POSIX_C_SOURCE 200809L
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static const char *env_or(const char *key, const char *fallback) {
    const char *value = getenv(key);
    return value ? value : fallback;
}
int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--build-info") == 0) {
        const char *signal_parent = getenv("MOCK_SIGNAL_PARENT");
        if (signal_parent) {
            int sig = strcmp(signal_parent, "HUP") == 0 ? SIGHUP : SIGTERM;
            if (kill(getppid(), sig) != 0) return 97;
        }
        const char *mode = env_or("MOCK_EMBEDDED_MODE", "aot");
        const char *refs = env_or("MOCK_EMBEDDED_REFS", "compressed32");
        const char *guest = strcmp(mode, "aot") == 0
            ? "interpreter-no-jit" : "runtime-selected";
        printf("{\"build_mode\":\"%s\",\"default_execution_mode\":\"%s\",\"managed_reference_mode\":\"%s\",\"guest_execution\":\"%s\"}\n",
               mode, mode, refs, guest);
        if (getenv("MOCK_EXTRA_BUILD_INFO")) puts("unexpected extra output");
        if (getenv("MOCK_BUILD_INFO_STDERR")) fputs("unexpected manifest diagnostic\n", stderr);
        if (getenv("MOCK_BUILD_INFO_TRAILING_BLANK")) puts("");
        if (getenv("MOCK_BUILD_INFO_NUL")) fputc(0, stdout);
        if (getenv("MOCK_BUILD_INFO_NONZERO")) return 17;
        return 0;
    }
    if (argc >= 2 && strcmp(argv[1], "--mode=jit") == 0) {
        int status = atoi(env_or("MOCK_JIT_STATUS", "2"));
        if (status == 2) {
            if (getenv("MOCK_JIT_STDOUT_SPOOF")) {
                puts("This executable was built AOT-only; --mode=jit cannot enable runtime compilation.");
                fputs("oreslang: unknown CLI option\n", stderr);
            }
            else if (getenv("MOCK_JIT_UNRELATED_USAGE_ERROR"))
                fputs("oreslang: unknown CLI option\n", stderr);
            else if (getenv("MOCK_JIT_REASON_AFTER_CLI_ERROR")) {
                fputs("oreslang: unknown CLI option\n", stderr);
                fputs("oreslang: This executable was built AOT-only; --mode=jit cannot enable runtime compilation. Rebuild with native-hybrid.\n", stderr);
            }
            else if (getenv("MOCK_JIT_REASON_BEFORE_CLI_ERROR")) {
                fputs("oreslang: This executable was built AOT-only; --mode=jit cannot enable runtime compilation. Rebuild with native-hybrid.\n", stderr);
                fputs("oreslang: unknown CLI option\n", stderr);
            }
            else if (getenv("MOCK_JIT_REASON_EMBEDDED_IN_PARSE_ERROR"))
                fputs("oreslang: parse error: This executable was built AOT-only; --mode=jit cannot enable runtime compilation.\n", stderr);
            else if (getenv("MOCK_JIT_REFUSAL_NUL")) {
                fputs("oreslang: This executable was built AOT-only; --mode=jit cannot enable runtime compilation. Rebuild with native-hybrid.", stderr);
                fputc(0, stderr);
                fputc('\n', stderr);
            }
            else if (getenv("MOCK_JIT_REFUSAL_CR")) {
                fputs("oreslang: This executable was built AOT-only; --mode=jit cannot enable runtime compilation. Rebuild with native-hybrid.", stderr);
                fputc('\r', stderr);
                fputc('\n', stderr);
            }
            else if (getenv("MOCK_JIT_REFUSAL_WITH_EXTRA_TEXT"))
                fputs("oreslang: This executable was built AOT-only; --mode=jit cannot enable runtime compilation. Rebuild with native-hybrid. FATAL unrelated parse failure\n", stderr);
            else if (getenv("MOCK_JIT_WRONG_MODE_REASON"))
                fputs("oreslang: unrelated runtime compilation setting\n", stderr);
            else if (strcmp(env_or("MOCK_EMBEDDED_MODE", "aot"), "aot") == 0)
                fputs("oreslang: This executable was built AOT-only; --mode=jit cannot enable runtime compilation. Rebuild with native-hybrid.\n", stderr);
            else
                fputs("oreslang: Native hybrid executables accept --mode=hybrid or --mode=aot, not JVM --mode=jit\n", stderr);
        }
        return status;
    }
    if (getenv("JAVA_HOME") || getenv("CLASSPATH")
            || getenv("JAVA_TOOL_OPTIONS") || getenv("JDK_JAVA_OPTIONS")) {
        fputs("Java launcher environment leaked\n", stderr);
        return 7;
    }
    if (getenv("MOCK_BAD_PROGRAM_STATUS")) return 13;
    if (argc >= 3 && strcmp(argv[1], "--check") == 0) {
        if (access(argv[2], F_OK) != 0) return 9;
        if (strstr(argv[2], "private-actor-sharing-invalid.ores") != NULL) {
            if (getenv("MOCK_ACCEPT_INVALID_PRIVATE_ACTOR")) return 0;
            if (getenv("MOCK_ACTOR_STDOUT_SPOOF")) {
                puts("Oreslang isolate denies capability SHARED_MEMORY required by SharedMutex<T>");
                fputs("Oreslang parse error at 1:1: unexpected token\n", stderr);
            }
            else if (getenv("MOCK_ACTOR_DENIAL_EMBEDDED"))
                fputs("Oreslang parse error: Oreslang isolate denies capability SHARED_MEMORY\n", stderr);
            else if (getenv("MOCK_ACTOR_DENIAL_AFTER_PARSE_ERROR")) {
                fputs("Oreslang parse error at 1:1: unexpected token\n", stderr);
                fputs("Oreslang isolate denies capability SHARED_MEMORY required by SharedMutex<T>\n", stderr);
            }
            else if (getenv("MOCK_ACTOR_DENIAL_BEFORE_PARSE_ERROR")) {
                fputs("Oreslang isolate denies capability SHARED_MEMORY required by SharedMutex<T>\n", stderr);
                fputs("Oreslang parse error at 1:1: unexpected token\n", stderr);
            }
            else if (getenv("MOCK_ACTOR_DENIAL_NUL")) {
                fputs("Oreslang isolate denies capability SHARED_MEMORY required by SharedMutex<T>", stderr);
                fputc(0, stderr);
                fputc('\n', stderr);
            }
            else if (getenv("MOCK_ACTOR_DENIAL_CR")) {
                fputs("Oreslang isolate denies capability SHARED_MEMORY required by SharedMutex<T>", stderr);
                fputc('\r', stderr);
                fputc('\n', stderr);
            }
            else if (getenv("MOCK_WRONG_ACTOR_REJECTION"))
                fputs("Oreslang parse error at 1:1: unexpected token\n", stderr);
            else if (getenv("MOCK_WRONG_ACTOR_CAPABILITY"))
                fputs("Oreslang isolate denies capability NETWORK required by actor\n", stderr);
            else
                fputs("Oreslang isolate denies capability SHARED_MEMORY required by SharedMutex<T>\n", stderr);
            return getenv("MOCK_BAD_DENIAL_STATUS") ? 2 : 1;
        }
        if (strstr(argv[2], "shared-private-actor-isolation.ores") != NULL) {
            if (getenv("MOCK_REJECT_VALID_ACTOR")) return 1;
            if (getenv("MOCK_ACTOR_CHECK_EXECUTES_GUEST")) puts("actor guest output");
            if (getenv("MOCK_ACTOR_CHECK_NUL_STDOUT")) fputc(0, stdout);
            if (getenv("MOCK_ACTOR_CHECK_NUL_STDERR")) fputc(0, stderr);
            if (getenv("MOCK_ACTOR_CHECK_STDERR"))
                fputs("unexpected actor admission diagnostic\n", stderr);
            return 0;
        }
        if (getenv("MOCK_CHECK_EXECUTES_GUEST")) puts("120:9:13");
        if (getenv("MOCK_CHECK_NUL_STDOUT")) fputc(0, stdout);
        if (getenv("MOCK_CHECK_NUL_STDERR")) fputc(0, stderr);
        if (getenv("MOCK_CHECK_STDERR"))
            fputs("unexpected source admission diagnostic\n", stderr);
        return 0;
    }
    if (argc >= 3 && (strcmp(argv[1], "--mode=aot") == 0
            || strcmp(argv[1], "--mode=hybrid") == 0)) {
        if (access(argv[2], F_OK) != 0) return 9;
        if (getenv("MOCK_GUEST_STDERR") && strstr(argv[2], "callables.ores") != NULL)
            fputs("Oreslang runtime unexpectedly wrote stderr\n", stderr);
        if (strstr(argv[2], "callables.ores") != NULL) {
            if (!getenv("MOCK_SILENT_CALLABLES")) {
                if (getenv("MOCK_BAD_CALLABLES")) puts("120:9:12");
                else puts("120:9:13");
            }
            if (getenv("MOCK_TRAILING_BLANK_CALLABLES")) puts("");
        }
        if (strstr(argv[2], "native-channel-select-smoke.ores") != NULL) {
            if (!getenv("MOCK_EMPTY_CHANNEL_OUTPUT")) {
                if (getenv("MOCK_WRONG_CHANNEL_WINNER")) puts("wrong:9:7");
                else if (getenv("MOCK_LOST_CHANNEL_MESSAGE")) puts("first:7:");
                else puts("first:7:9");
            }
            if (getenv("MOCK_TRAILING_BLANK_CHANNEL")) puts("");
        }
        if (strstr(argv[2], "native-future-select-smoke.ores") != NULL) {
            if (!getenv("MOCK_EMPTY_FUTURE_OUTPUT")) {
                if (getenv("MOCK_WRONG_FUTURE_OUTPUT")) puts("channel:0");
                else puts("future:4");
            }
            if (getenv("MOCK_TRAILING_BLANK_FUTURE")) puts("");
        }
        if (strstr(argv[2], "strict-root-await.ores") != NULL) {
            if (!getenv("MOCK_EMPTY_AWAIT_OUTPUT")) {
                if (getenv("MOCK_WRONG_AWAIT_OUTPUT")) puts("41");
                else puts("42");
            }
            if (getenv("MOCK_TRAILING_BLANK_AWAIT")) puts("");
        }
        if (strstr(argv[2], "native-core-library.ores") != NULL) {
            if (!getenv("MOCK_EMPTY_CORE_OUTPUT")) {
                if (getenv("MOCK_BAD_CORE_OUTPUT"))
                    puts("9007199254740992|-999999999999999998|10");
                else
                    puts("9007199254740992|-999999999999999999|10");
            }
            if (getenv("MOCK_TRAILING_BLANK_CORE")) puts("");
        }
        if (strstr(argv[2], "native-testing.ores") != NULL) {
            if (!getenv("MOCK_EMPTY_TEST_OUTPUT")) {
                if (getenv("MOCK_EARLY_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0");
                if (!getenv("MOCK_MISSING_TEST_EVENTS")) {
                    if (!getenv("MOCK_LATE_ARITHMETIC_PASS")) {
                        if (getenv("MOCK_TEST_PASS_EXTRA_FIELD"))
                            puts("ORES_TEST|PASS|core/arithmetic/adds|FATAL|additional unexpected field");
                        else if (getenv("MOCK_TEST_PASS_MISSING_FIELD"))
                            puts("ORES_TEST|PASS|core/arithmetic/adds");
                        else if (getenv("MOCK_TEST_PASS_NUL")) {
                            fputs("ORES_TEST|PASS|core/arithmetic/adds|", stdout);
                            fputc(0, stdout);
                            fputc('\n', stdout);
                        }
                        else if (getenv("MOCK_TEST_PASS_CR")) {
                            fputs("ORES_TEST|PASS|core/arithmetic/adds|", stdout);
                            fputc('\r', stdout);
                            fputc('\n', stdout);
                        }
                        else if (getenv("MOCK_TEST_PASS_ANSI_ESCAPE")) {
                            fputs("ORES_TEST|PASS|core/arithmetic/adds|", stdout);
                            fputc('\033', stdout);
                            fputs("[31m\n", stdout);
                        }
                        else
                            puts("ORES_TEST|PASS|core/arithmetic/adds|");
                    }
                    if (getenv("MOCK_REPLACE_FLOAT_WITH_ARITHMETIC_PASS"))
                        puts("ORES_TEST|PASS|core/arithmetic/adds|");
                    else
                        puts("ORES_TEST|PASS|core/float/tolerance|");
                    puts("ORES_TEST|PASS|core/property/square|");
                    if (getenv("MOCK_WRONG_SKIP_ID"))
                        puts("ORES_TEST|SKIP|core/future/unrelated|not supported");
                    else
                        puts("ORES_TEST|SKIP|core/future/parallel-runner|not yet supported");
                    if (getenv("MOCK_DUPLICATE_TEST_PASS_ID"))
                        puts("ORES_TEST|PASS|core/arithmetic/adds|");
                    if (getenv("MOCK_DUPLICATE_TEST_SKIP_ID"))
                        puts("ORES_TEST|SKIP|core/future/parallel-runner|not yet supported");
                }
                if (getenv("MOCK_EXTRA_TEST_PASS"))
                    puts("ORES_TEST|PASS|core/extra/false-positive|");
                if (getenv("MOCK_EXTRA_TEST_FAIL"))
                    puts("ORES_TEST|FAIL|core/failure|unexpected failure");
                if (getenv("MOCK_STRAY_STDOUT"))
                    puts("FATAL: unexpected native test output");
                if (getenv("MOCK_BLANK_STDOUT_LINE"))
                    puts("");
                if (getenv("MOCK_BAD_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=0|failed=4|skipped=0|filtered=0");
                else if (!getenv("MOCK_EARLY_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0");
                if (getenv("MOCK_LATE_ARITHMETIC_PASS"))
                    puts("ORES_TEST|PASS|core/arithmetic/adds|");
                if (getenv("MOCK_DUPLICATE_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0");
                if (getenv("MOCK_CONFLICTING_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=0|failed=4|skipped=0|filtered=0");
                if (getenv("MOCK_TRAILING_BLANK_STDOUT"))
                    puts("");
            }
        }
        return 0;
    }
    return 12;
}
C_SOURCE
cc -std=c11 -Wall -Wextra -Werror -o "$tmp/native-fixture" "$tmp/mock.c"

cat > "$tmp/graal/bin/native-image" <<'MOCK_IMAGE'
#!/bin/sh
if [ "$#" -eq 1 ] && [ "$1" = --version ]; then
  echo "source-free fake native-image; no GraalVM used"
  exit 0
fi
exit 22
MOCK_IMAGE
chmod +x "$tmp/graal/bin/native-image"

cat > "$tmp/bin/mvn" <<'MOCK_MAVEN'
#!/bin/sh
set -eu
printf '%s\n' "$*" > "$MOCK_MVN_LOG"
case " $* " in
  *' clean package '*) : ;;
  *) echo 'clean package required' >&2; exit 18 ;;
esac
case "${MOCK_EXPECT_PROFILE_REFS:-}" in
  native64)
    case " $* " in
      *native-ref64*) : ;;
      *) echo 'missing native-ref64 Maven profile' >&2; exit 21 ;;
    esac ;;
  compressed32)
    case " $* " in
      *native-ref64*) echo 'unexpected native-ref64 Maven profile' >&2; exit 22 ;;
      *) : ;;
    esac ;;
esac
case "${MOCK_EXPECT_GC:-serial}" in
  epsilon)
    case " $* " in
      *native-epsilon*) : ;;
      *) echo 'missing native-epsilon Maven profile' >&2; exit 25 ;;
    esac ;;
  serial)
    case " $* " in
      *native-epsilon*) echo 'unexpected native-epsilon Maven profile' >&2; exit 26 ;;
      *) : ;;
    esac ;;
esac
case "${MOCK_EXPECT_OFFLINE:-}" in
  yes)
    case " $* " in
      *' -o '*) : ;;
      *) echo 'Maven offline flag -o required' >&2; exit 23 ;;
    esac ;;
  no)
    case " $* " in
      *' -o '*) echo 'unexpected Maven offline flag -o' >&2; exit 24 ;;
      *) : ;;
    esac ;;
esac
if [ "${MOCK_MVN_FAIL:-0}" = 1 ]; then exit 19; fi
rm -rf target
mkdir -p target
case " $* " in
  *' -Pnative-aot'* ) target=target/ores-aot ;;
  *' -Pnative-hybrid'* ) target=target/ores-hybrid ;;
  *) echo 'wrong Maven profile' >&2; exit 20 ;;
esac
if [ "${MOCK_BAD_BINARY:-0}" = 1 ]; then
  printf '#!/bin/sh\necho not a native binary\n' > "$target"
else
  cp "$MOCK_BINARY" "$target"
fi
chmod +x "$target"
MOCK_MAVEN
chmod +x "$tmp/bin/mvn"

# Track verifier mktemp paths so every success and failure path proves cleanup.
# Resolve the real tool before shadowing PATH, avoiding recursive invocation.
mock_real_mktemp="$(command -v mktemp)"
cat > "$tmp/bin/mktemp" <<'MOCK_MKTEMP'
#!/bin/sh
set -eu
allocation_count="$(cat "$MOCK_MKTEMP_COUNT_LOG")"
allocation_count=$((allocation_count + 1))
printf '%s\n' "$allocation_count" > "$MOCK_MKTEMP_COUNT_LOG"
if [ "$allocation_count" -eq "${MOCK_MKTEMP_FAIL_AT:-0}" ]; then
  echo "mock mktemp refused allocation $allocation_count" >&2
  exit 39
fi
made="$("$MOCK_REAL_MKTEMP" "$@")"
printf '%s\n' "$made" >> "$MOCK_MKTEMP_LOG"
printf '%s\n' "$made"
MOCK_MKTEMP
chmod +x "$tmp/bin/mktemp"
export MOCK_REAL_MKTEMP="$mock_real_mktemp" MOCK_MKTEMP_LOG="$tmp/mktemp-created.log"
export MOCK_MKTEMP_COUNT_LOG="$tmp/mktemp-allocation-count.log"

export JAVA_HOME="$tmp/graal" PATH="$tmp/bin:$PATH"
export MOCK_BINARY="$tmp/native-fixture" MOCK_MVN_LOG="$tmp/mvn.log"
# These variables must be cleared for *all* native source executions.
export CLASSPATH=some-java-path JAVA_TOOL_OPTIONS=-Xmx32m JDK_JAVA_OPTIONS=-Xmx32m

assert_case() {
  name="$1"; expected="$2"; mode="$3"; refs="$4"
  shift 4
  : > "$MOCK_MKTEMP_LOG"
  printf '0\n' > "$MOCK_MKTEMP_COUNT_LOG"
  if (cd "$tmp/work" && env "$@" sh "$script" "$mode" "$refs") > "$tmp/stdout" 2> "$tmp/stderr"; then
    result=0
  else
    result=$?
  fi
  # Native verifier must not leak its manifest, guest or test-output files,
  # including when a deliberate negative exits before completing all stages.
  while IFS= read -r temp_path; do
    if [ -e "$temp_path" ]; then
      echo "FAILED: $name leaked temporary verifier file: $temp_path" >&2
      exit 1
    fi
  done < "$MOCK_MKTEMP_LOG"
  if { [ "$expected" = success ] && [ "$result" -ne 0 ]; } \
      || { [ "$expected" = failure ] && [ "$result" -eq 0 ]; }; then
    echo "FAILED: $name (expected $expected, got exit $result)" >&2
    cat "$tmp/stdout" "$tmp/stderr" >&2
    exit 1
  fi
  if [ "$expected" = failure ]; then
    # Negative cases must fail for their INTENDED reason. A missing native
    # binary, an unrelated Maven error or a premature parse failure must not
    # make a later capability/JIT/channel assertion look proven.
    expected_status=1
    expected_diagnostic=
    case "$name" in
      'mktemp failure at allocation '*)
        expected_status=39
        expected_diagnostic="mock mktemp refused allocation ${name##* }" ;;
      'termination during build-info cleans temporary files') expected_status=143 ;;
      'hangup during build-info cleans temporary files') expected_status=129 ;;
      'invalid offline mode denied') expected_status=2; expected_diagnostic='ORES_NATIVE_IMAGE_OFFLINE must be 0 or 1' ;;
      'invalid mode') expected_status=2; expected_diagnostic='invalid image mode: invalid' ;;
      'invalid native GC mode') expected_status=2; expected_diagnostic='ORES_NATIVE_GC_MODE must be serial or epsilon' ;;
      'Epsilon refused in hybrid Native Image') expected_status=2; expected_diagnostic='Epsilon GC is only admitted with full native AOT' ;;
      'invalid refs') expected_status=2; expected_diagnostic='invalid reference mode: invalid' ;;
      'Maven failure') expected_status=19 ;;
      'script wrapper rather than native binary') expected_diagnostic='artifact is not an OS-native executable' ;;
      'wrong baked reference mode'|'wrong baked image mode'|'extra build-info lines'|\
      'manifest trailing blank stdout'|'manifest NUL stdout')
        expected_diagnostic='native build-info does not match canonical' ;;
      'manifest stderr diagnostic'|'manifest command nonzero exit')
        expected_diagnostic='native --build-info failed or emitted stderr diagnostics' ;;
      'source execution failure') expected_status=13 ;;
      'check-only mistakenly executes guest'|'check-only reports stderr diagnostic with zero exit'|\
      'check-only emits NUL stdout'|'check-only emits NUL stderr')
        expected_diagnostic='native --check unexpectedly emitted diagnostics or guest output' ;;
      'silent callables guest execution'|'incorrect lexical closure result'|'trailing blank callables stdout')
        expected_diagnostic='native callables guest output differs from expected value' ;;
      'guest stderr despite correct stdout')
        expected_diagnostic='native guest unexpectedly emitted stderr diagnostics' ;;
      'private actor capability violation admitted')
        expected_diagnostic='expected private actor capability denial with CLI status 1, got 0' ;;
      'private actor denial uses wrong CLI exit status')
        expected_diagnostic='expected private actor capability denial with CLI status 1, got 2' ;;
      'valid shared/private actor rejected') : ;;
      'actor rejected for unrelated parse error'|'actor rejected for unrelated network capability'|\
      'actor denial spoofed on stdout'|'actor denial embedded in parse error'|\
      'actor denial after unrelated parse error'|'actor denial before unrelated parse error'|\
      'actor denial with NUL'|'actor denial with CR')
        expected_diagnostic='private actor negative fixture failed without a capability-denial diagnostic' ;;
      'actor check-only emitted guest output'|'actor check-only reports stderr diagnostic with zero exit'|\
      'actor check-only emits NUL stdout'|'actor check-only emits NUL stderr')
        expected_diagnostic='native actor --check unexpectedly emitted diagnostics or guest output' ;;
      'silent fake image without guest-core output'|'wrong native core numeric result'|'trailing blank core stdout')
        expected_diagnostic='native Oreslang core-library output differs from expected value' ;;
      'silent native channel execution'|'wrong native channel select winner'|\
      'lost unselected native channel message'|'trailing blank channel stdout')
        expected_diagnostic='native channel/select result differs from expected first-winner and retained-message output' ;;
      'silent native Future/select execution'|'incorrect native Future/select result'|'trailing blank Future/select stdout')
        expected_diagnostic='native Future/select output differs from expected result' ;;
      'silent native root-await execution'|'incorrect native root-await result'|'trailing blank root-await stdout')
        expected_diagnostic='native root-await result differs from expected value' ;;
      'silent fake image without guest-test output'|'guest test reports failures'|\
      'valid summary without actual PASS/SKIP records'|'duplicate valid summary'|\
      'valid plus conflicting summary'|'valid summary alongside FAIL event'|\
      'valid summary alongside excess PASS event'|'duplicated PASS replacing missing PASS'|\
      'duplicate arithmetic PASS identity'|'wrong SKIP test identity'|'duplicate SKIP identity'|\
      'summary emitted before test events'|'PASS emitted after final summary'|\
      'stray stdout with valid test events and summary'|'unexpected blank stdout record'|\
      'trailing blank stdout after valid summary'|\
      'test PASS event has extra pipe field'|'test PASS event lacks detail field'|\
      'test PASS event contains NUL'|'test PASS event contains CR'|\
      'test PASS event contains ANSI escape')
        expected_diagnostic='native executable did not produce the exact unique PASS/SKIP guest tests and summary' ;;
      'unrelated CLI error impersonates AOT JIT rejection'|\
      'wrong AOT JIT refusal reason'|'wrong hybrid JIT refusal reason'|\
      'JIT reason spoofed on stdout'|\
      'JIT reason after unrelated CLI error'|'JIT reason before unrelated CLI error'|\
      'JIT reason embedded inside unrelated parse error'|\
      'JIT refusal with NUL'|'JIT refusal with CR'|'JIT refusal with appended fatal text')
        expected_diagnostic='native executable refused JIT without the required build-mode diagnostic' ;;
      'native image accepting JVM JIT')
        expected_diagnostic='expected --mode=jit to be rejected with CLI status 2, got 0' ;;
      'unexpected JIT refusal exit status')
        expected_diagnostic='expected --mode=jit to be rejected with CLI status 2, got 1' ;;
      *) echo "FAILED: unclassified negative test: $name" >&2; exit 1 ;;
    esac
    if [ "$result" -ne "$expected_status" ]; then
      echo "FAILED: $name returned $result instead of expected failure status $expected_status" >&2
      cat "$tmp/stdout" "$tmp/stderr" >&2
      exit 1
    fi
    if [ -n "$expected_diagnostic" ]; then
      if ! grep -F "$expected_diagnostic" "$tmp/stderr" >/dev/null; then
        echo "FAILED: $name did not reach its intended rejection stage" >&2
        cat "$tmp/stdout" "$tmp/stderr" >&2
        exit 1
      fi
    elif [ "$name" = 'valid shared/private actor rejected' ]; then
      # This mock returns status 1 directly, without a diagnostic. It must at
      # least have passed the earlier native build, manifest and callables run.
      if ! grep -Fx '120:9:13' "$tmp/stdout" >/dev/null || [ -s "$tmp/stderr" ]; then
        echo "FAILED: actor rejection occurred before linked actor admission" >&2
        exit 1
      fi
    fi
  fi
  echo "PASS (mocked): $name"
}

assert_case 'AOT compressed32' success aot compressed32 MOCK_EMBEDDED_MODE=aot MOCK_EMBEDDED_REFS=compressed32 MOCK_EXPECT_PROFILE_REFS=compressed32
assert_case 'Epsilon AOT compressed32 opt-in' success aot compressed32 ORES_NATIVE_GC_MODE=epsilon MOCK_EXPECT_GC=epsilon
assert_case 'Epsilon AOT native64 opt-in' success aot native64 ORES_NATIVE_GC_MODE=epsilon MOCK_EXPECT_GC=epsilon MOCK_EMBEDDED_REFS=native64 MOCK_EXPECT_PROFILE_REFS=native64
assert_case 'Epsilon refused in hybrid Native Image' failure hybrid compressed32 ORES_NATIVE_GC_MODE=epsilon
assert_case 'invalid native GC mode' failure aot compressed32 ORES_NATIVE_GC_MODE=epsilon,serial
assert_case 'AOT native64' success aot native64 MOCK_EMBEDDED_MODE=aot MOCK_EMBEDDED_REFS=native64 MOCK_EXPECT_PROFILE_REFS=native64
assert_case 'hybrid compressed32' success hybrid compressed32 MOCK_EMBEDDED_MODE=hybrid MOCK_EMBEDDED_REFS=compressed32 MOCK_EXPECT_PROFILE_REFS=compressed32
assert_case 'hybrid native64' success hybrid native64 MOCK_EMBEDDED_MODE=hybrid MOCK_EMBEDDED_REFS=native64 MOCK_EXPECT_PROFILE_REFS=native64
assert_case 'AOT forced-offline carrier build' success aot compressed32 ORES_NATIVE_IMAGE_OFFLINE=1 MOCK_EXPECT_OFFLINE=yes
assert_case 'hybrid ref64 forced-offline carrier build' success hybrid native64 ORES_NATIVE_IMAGE_OFFLINE=1 MOCK_EXPECT_OFFLINE=yes MOCK_EMBEDDED_MODE=hybrid MOCK_EMBEDDED_REFS=native64 MOCK_EXPECT_PROFILE_REFS=native64
# Inject allocation failure at every of the verifier's eight allocations. All
# previously allocated temporary paths must be removed by the exit trap.
assert_case 'mktemp failure at allocation 1' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=1
assert_case 'mktemp failure at allocation 2' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=2
assert_case 'mktemp failure at allocation 3' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=3
assert_case 'mktemp failure at allocation 4' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=4
assert_case 'mktemp failure at allocation 5' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=5
assert_case 'mktemp failure at allocation 6' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=6
assert_case 'mktemp failure at allocation 7' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=7
assert_case 'mktemp failure at allocation 8' failure aot compressed32 MOCK_MKTEMP_FAIL_AT=8
# SIGTERM/SIGHUP must invoke cleanup through the EXIT trap, not leave the
# three mktemp files already allocated when native --build-info is invoked.
assert_case 'termination during build-info cleans temporary files' failure aot compressed32 MOCK_SIGNAL_PARENT=TERM
assert_case 'hangup during build-info cleans temporary files' failure aot compressed32 MOCK_SIGNAL_PARENT=HUP
assert_case 'invalid offline mode denied' failure aot compressed32 ORES_NATIVE_IMAGE_OFFLINE=sometimes
assert_case 'invalid mode' failure invalid compressed32
assert_case 'invalid refs' failure aot invalid
assert_case 'Maven failure' failure aot compressed32 MOCK_MVN_FAIL=1
assert_case 'script wrapper rather than native binary' failure aot compressed32 MOCK_BAD_BINARY=1
assert_case 'wrong baked reference mode' failure aot compressed32 MOCK_EMBEDDED_MODE=aot MOCK_EMBEDDED_REFS=native64
assert_case 'wrong baked image mode' failure aot compressed32 MOCK_EMBEDDED_MODE=hybrid
assert_case 'extra build-info lines' failure aot compressed32 MOCK_EXTRA_BUILD_INFO=1
assert_case 'manifest stderr diagnostic' failure aot compressed32 MOCK_BUILD_INFO_STDERR=1
assert_case 'manifest trailing blank stdout' failure aot compressed32 MOCK_BUILD_INFO_TRAILING_BLANK=1
assert_case 'manifest NUL stdout' failure aot compressed32 MOCK_BUILD_INFO_NUL=1
assert_case 'manifest command nonzero exit' failure aot compressed32 MOCK_BUILD_INFO_NONZERO=1
assert_case 'source execution failure' failure aot compressed32 MOCK_BAD_PROGRAM_STATUS=1
assert_case 'check-only mistakenly executes guest' failure aot compressed32 MOCK_CHECK_EXECUTES_GUEST=1
assert_case 'check-only reports stderr diagnostic with zero exit' failure aot compressed32 MOCK_CHECK_STDERR=1
grep -Fx 'native --check unexpectedly emitted diagnostics or guest output' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: check-only stderr diagnostic did not reach the output gate' >&2
  exit 1
}
assert_case 'check-only emits NUL stdout' failure aot compressed32 MOCK_CHECK_NUL_STDOUT=1
assert_case 'check-only emits NUL stderr' failure aot compressed32 MOCK_CHECK_NUL_STDERR=1
assert_case 'silent callables guest execution' failure aot compressed32 MOCK_SILENT_CALLABLES=1
assert_case 'incorrect lexical closure result' failure aot compressed32 MOCK_BAD_CALLABLES=1
assert_case 'trailing blank callables stdout' failure aot compressed32 MOCK_TRAILING_BLANK_CALLABLES=1
assert_case 'guest stderr despite correct stdout' failure aot compressed32 MOCK_GUEST_STDERR=1
assert_case 'private actor capability violation admitted' failure aot compressed32 MOCK_ACCEPT_INVALID_PRIVATE_ACTOR=1
assert_case 'private actor denial uses wrong CLI exit status' failure aot compressed32 MOCK_BAD_DENIAL_STATUS=1
assert_case 'valid shared/private actor rejected' failure aot compressed32 MOCK_REJECT_VALID_ACTOR=1
assert_case 'actor rejected for unrelated parse error' failure aot compressed32 MOCK_WRONG_ACTOR_REJECTION=1
grep -Fx 'private actor negative fixture failed without a capability-denial diagnostic' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: unrelated parse error was not rejected at the actor capability gate' >&2
  exit 1
}
assert_case 'actor rejected for unrelated network capability' failure aot compressed32 MOCK_WRONG_ACTOR_CAPABILITY=1
assert_case 'actor denial spoofed on stdout' failure aot compressed32 MOCK_ACTOR_STDOUT_SPOOF=1
assert_case 'actor denial embedded in parse error' failure aot compressed32 MOCK_ACTOR_DENIAL_EMBEDDED=1
assert_case 'actor denial after unrelated parse error' failure aot compressed32 MOCK_ACTOR_DENIAL_AFTER_PARSE_ERROR=1
assert_case 'actor denial before unrelated parse error' failure aot compressed32 MOCK_ACTOR_DENIAL_BEFORE_PARSE_ERROR=1
assert_case 'actor denial with NUL' failure aot compressed32 MOCK_ACTOR_DENIAL_NUL=1
assert_case 'actor denial with CR' failure aot compressed32 MOCK_ACTOR_DENIAL_CR=1
grep -Fx 'private actor negative fixture failed without a capability-denial diagnostic' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: unrelated network capability was not rejected at the actor capability gate' >&2
  exit 1
}
assert_case 'actor check-only emitted guest output' failure aot compressed32 MOCK_ACTOR_CHECK_EXECUTES_GUEST=1
assert_case 'actor check-only reports stderr diagnostic with zero exit' failure aot compressed32 MOCK_ACTOR_CHECK_STDERR=1
grep -Fx 'native actor --check unexpectedly emitted diagnostics or guest output' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: actor check stderr diagnostic did not reach the output gate' >&2
  exit 1
}
assert_case 'actor check-only emits NUL stdout' failure aot compressed32 MOCK_ACTOR_CHECK_NUL_STDOUT=1
assert_case 'actor check-only emits NUL stderr' failure aot compressed32 MOCK_ACTOR_CHECK_NUL_STDERR=1
assert_case 'silent fake image without guest-core output' failure aot compressed32 MOCK_EMPTY_CORE_OUTPUT=1
assert_case 'wrong native core numeric result' failure aot compressed32 MOCK_BAD_CORE_OUTPUT=1
assert_case 'trailing blank core stdout' failure aot compressed32 MOCK_TRAILING_BLANK_CORE=1
assert_case 'silent native channel execution' failure aot compressed32 MOCK_EMPTY_CHANNEL_OUTPUT=1
assert_case 'wrong native channel select winner' failure aot compressed32 MOCK_WRONG_CHANNEL_WINNER=1
assert_case 'lost unselected native channel message' failure aot compressed32 MOCK_LOST_CHANNEL_MESSAGE=1
assert_case 'trailing blank channel stdout' failure aot compressed32 MOCK_TRAILING_BLANK_CHANNEL=1
assert_case 'silent native Future/select execution' failure aot compressed32 MOCK_EMPTY_FUTURE_OUTPUT=1
assert_case 'incorrect native Future/select result' failure aot compressed32 MOCK_WRONG_FUTURE_OUTPUT=1
assert_case 'trailing blank Future/select stdout' failure aot compressed32 MOCK_TRAILING_BLANK_FUTURE=1
assert_case 'silent native root-await execution' failure aot compressed32 MOCK_EMPTY_AWAIT_OUTPUT=1
assert_case 'incorrect native root-await result' failure aot compressed32 MOCK_WRONG_AWAIT_OUTPUT=1
assert_case 'trailing blank root-await stdout' failure aot compressed32 MOCK_TRAILING_BLANK_AWAIT=1
assert_case 'silent fake image without guest-test output' failure aot compressed32 MOCK_EMPTY_TEST_OUTPUT=1
assert_case 'guest test reports failures' failure aot compressed32 MOCK_BAD_TEST_SUMMARY=1
assert_case 'valid summary without actual PASS/SKIP records' failure aot compressed32 MOCK_MISSING_TEST_EVENTS=1
assert_case 'duplicate valid summary' failure aot compressed32 MOCK_DUPLICATE_TEST_SUMMARY=1
assert_case 'valid plus conflicting summary' failure aot compressed32 MOCK_CONFLICTING_TEST_SUMMARY=1
assert_case 'valid summary alongside FAIL event' failure aot compressed32 MOCK_EXTRA_TEST_FAIL=1
assert_case 'stray stdout with valid test events and summary' failure aot compressed32 MOCK_STRAY_STDOUT=1
grep -Fx 'native executable did not produce the exact unique PASS/SKIP guest tests and summary' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: stray stdout was rejected by the wrong native gate' >&2
  exit 1
}
assert_case 'unexpected blank stdout record' failure aot compressed32 MOCK_BLANK_STDOUT_LINE=1
grep -Fx 'native executable did not produce the exact unique PASS/SKIP guest tests and summary' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: blank stdout record was rejected by the wrong native gate' >&2
  exit 1
}
assert_case 'trailing blank stdout after valid summary' failure aot compressed32 MOCK_TRAILING_BLANK_STDOUT=1
grep -Fx 'native executable did not produce the exact unique PASS/SKIP guest tests and summary' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: trailing blank stdout record escaped the raw-output gate' >&2
  exit 1
}
assert_case 'valid summary alongside excess PASS event' failure aot compressed32 MOCK_EXTRA_TEST_PASS=1
assert_case 'duplicated PASS replacing missing PASS' failure aot compressed32 MOCK_REPLACE_FLOAT_WITH_ARITHMETIC_PASS=1
assert_case 'duplicate arithmetic PASS identity' failure aot compressed32 MOCK_DUPLICATE_TEST_PASS_ID=1
assert_case 'wrong SKIP test identity' failure aot compressed32 MOCK_WRONG_SKIP_ID=1
assert_case 'duplicate SKIP identity' failure aot compressed32 MOCK_DUPLICATE_TEST_SKIP_ID=1
assert_case 'summary emitted before test events' failure aot compressed32 MOCK_EARLY_TEST_SUMMARY=1
assert_case 'PASS emitted after final summary' failure aot compressed32 MOCK_LATE_ARITHMETIC_PASS=1
assert_case 'test PASS event has extra pipe field' failure aot compressed32 MOCK_TEST_PASS_EXTRA_FIELD=1
assert_case 'test PASS event lacks detail field' failure aot compressed32 MOCK_TEST_PASS_MISSING_FIELD=1
assert_case 'test PASS event contains NUL' failure aot compressed32 MOCK_TEST_PASS_NUL=1
assert_case 'test PASS event contains CR' failure aot compressed32 MOCK_TEST_PASS_CR=1
assert_case 'test PASS event contains ANSI escape' failure aot compressed32 MOCK_TEST_PASS_ANSI_ESCAPE=1
assert_case 'unrelated CLI error impersonates AOT JIT rejection' failure aot compressed32 MOCK_JIT_UNRELATED_USAGE_ERROR=1
grep -Fx 'native executable refused JIT without the required build-mode diagnostic' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: JIT-related negative case did not reach the mode-specific refusal check' >&2
  exit 1
}
assert_case 'wrong AOT JIT refusal reason' failure aot compressed32 MOCK_JIT_WRONG_MODE_REASON=1
assert_case 'JIT reason spoofed on stdout' failure aot compressed32 MOCK_JIT_STDOUT_SPOOF=1
assert_case 'JIT reason after unrelated CLI error' failure aot compressed32 MOCK_JIT_REASON_AFTER_CLI_ERROR=1
assert_case 'JIT reason before unrelated CLI error' failure aot compressed32 MOCK_JIT_REASON_BEFORE_CLI_ERROR=1
assert_case 'JIT reason embedded inside unrelated parse error' failure aot compressed32 MOCK_JIT_REASON_EMBEDDED_IN_PARSE_ERROR=1
assert_case 'JIT refusal with NUL' failure aot compressed32 MOCK_JIT_REFUSAL_NUL=1
assert_case 'JIT refusal with CR' failure aot compressed32 MOCK_JIT_REFUSAL_CR=1
assert_case 'JIT refusal with appended fatal text' failure aot compressed32 MOCK_JIT_REFUSAL_WITH_EXTRA_TEXT=1
grep -Fx 'native executable refused JIT without the required build-mode diagnostic' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: JIT-related negative case did not reach the mode-specific refusal check' >&2
  exit 1
}
assert_case 'wrong hybrid JIT refusal reason' failure hybrid compressed32 MOCK_EMBEDDED_MODE=hybrid MOCK_JIT_WRONG_MODE_REASON=1
grep -Fx 'native executable refused JIT without the required build-mode diagnostic' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: JIT-related negative case did not reach the mode-specific refusal check' >&2
  exit 1
}
assert_case 'native image accepting JVM JIT' failure aot compressed32 MOCK_JIT_STATUS=0
assert_case 'unexpected JIT refusal exit status' failure aot compressed32 MOCK_JIT_STATUS=1

echo 'MOCKED_NATIVE_GATE_CONTRACT_TESTS_PASSED (not a native-image proof)'

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
for name in callables together native-core-library native-testing shared-private-actor-isolation private-actor-sharing-invalid; do
  echo '// placeholder: not Oreslang source' > "$tmp/work/examples/$name.ores"
done

# Compile a native fixture with programmable behavior to exercise validation.
cat > "$tmp/mock.c" <<'C_SOURCE'
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
        const char *mode = env_or("MOCK_EMBEDDED_MODE", "aot");
        const char *refs = env_or("MOCK_EMBEDDED_REFS", "compressed32");
        const char *guest = strcmp(mode, "aot") == 0
            ? "interpreter-no-jit" : "runtime-selected";
        printf("{\"build_mode\":\"%s\",\"default_execution_mode\":\"%s\",\"managed_reference_mode\":\"%s\",\"guest_execution\":\"%s\"}\n",
               mode, mode, refs, guest);
        if (getenv("MOCK_EXTRA_BUILD_INFO")) puts("unexpected extra output");
        return 0;
    }
    if (argc >= 2 && strcmp(argv[1], "--mode=jit") == 0)
        return atoi(env_or("MOCK_JIT_STATUS", "2"));
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
            if (getenv("MOCK_WRONG_ACTOR_REJECTION"))
                fputs("Oreslang parse error at 1:1: unexpected token\n", stderr);
            else if (getenv("MOCK_WRONG_ACTOR_CAPABILITY"))
                fputs("Oreslang isolate denies capability NETWORK required by actor\n", stderr);
            else
                fputs("Oreslang isolate denies capability SHARED_MEMORY required by SharedMutex<T>\n", stderr);
            return 1;
        }
        if (strstr(argv[2], "shared-private-actor-isolation.ores") != NULL) {
            if (getenv("MOCK_REJECT_VALID_ACTOR")) return 1;
            if (getenv("MOCK_ACTOR_CHECK_EXECUTES_GUEST")) puts("actor guest output");
            return 0;
        }
        if (getenv("MOCK_CHECK_EXECUTES_GUEST")) puts("120:9:13");
        return 0;
    }
    if (argc >= 3 && (strcmp(argv[1], "--mode=aot") == 0
            || strcmp(argv[1], "--mode=hybrid") == 0)) {
        if (access(argv[2], F_OK) != 0) return 9;
        if (strstr(argv[2], "callables.ores") != NULL) {
            if (!getenv("MOCK_SILENT_CALLABLES")) {
                if (getenv("MOCK_BAD_CALLABLES")) puts("120:9:12");
                else puts("120:9:13");
            }
        }
        if (strstr(argv[2], "native-core-library.ores") != NULL) {
            if (!getenv("MOCK_EMPTY_CORE_OUTPUT")) {
                if (getenv("MOCK_BAD_CORE_OUTPUT"))
                    puts("9007199254740992|-999999999999999998|10");
                else
                    puts("9007199254740992|-999999999999999999|10");
            }
        }
        if (strstr(argv[2], "native-testing.ores") != NULL) {
            if (!getenv("MOCK_EMPTY_TEST_OUTPUT")) {
                if (!getenv("MOCK_MISSING_TEST_EVENTS")) {
                    puts("ORES_TEST|PASS|core/arithmetic/adds|");
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
                if (getenv("MOCK_BAD_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=0|failed=4|skipped=0|filtered=0");
                else
                    puts("ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0");
                if (getenv("MOCK_DUPLICATE_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0");
                if (getenv("MOCK_CONFLICTING_TEST_SUMMARY"))
                    puts("ORES_TEST|SUMMARY|total=4|passed=0|failed=4|skipped=0|filtered=0");
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

export JAVA_HOME="$tmp/graal" PATH="$tmp/bin:$PATH"
export MOCK_BINARY="$tmp/native-fixture" MOCK_MVN_LOG="$tmp/mvn.log"
# These variables must be cleared for *all* native source executions.
export CLASSPATH=some-java-path JAVA_TOOL_OPTIONS=-Xmx32m JDK_JAVA_OPTIONS=-Xmx32m

assert_case() {
  name="$1"; expected="$2"; mode="$3"; refs="$4"
  shift 4
  if (cd "$tmp/work" && env "$@" sh "$script" "$mode" "$refs") > "$tmp/stdout" 2> "$tmp/stderr"; then
    result=0
  else
    result=$?
  fi
  if { [ "$expected" = success ] && [ "$result" -ne 0 ]; } \
      || { [ "$expected" = failure ] && [ "$result" -eq 0 ]; }; then
    echo "FAILED: $name (expected $expected, got exit $result)" >&2
    cat "$tmp/stdout" "$tmp/stderr" >&2
    exit 1
  fi
  echo "PASS (mocked): $name"
}

assert_case 'AOT compressed32' success aot compressed32 MOCK_EMBEDDED_MODE=aot MOCK_EMBEDDED_REFS=compressed32 MOCK_EXPECT_PROFILE_REFS=compressed32
assert_case 'AOT native64' success aot native64 MOCK_EMBEDDED_MODE=aot MOCK_EMBEDDED_REFS=native64 MOCK_EXPECT_PROFILE_REFS=native64
assert_case 'hybrid compressed32' success hybrid compressed32 MOCK_EMBEDDED_MODE=hybrid MOCK_EMBEDDED_REFS=compressed32 MOCK_EXPECT_PROFILE_REFS=compressed32
assert_case 'hybrid native64' success hybrid native64 MOCK_EMBEDDED_MODE=hybrid MOCK_EMBEDDED_REFS=native64 MOCK_EXPECT_PROFILE_REFS=native64
assert_case 'AOT forced-offline carrier build' success aot compressed32 ORES_NATIVE_IMAGE_OFFLINE=1 MOCK_EXPECT_OFFLINE=yes
assert_case 'hybrid ref64 forced-offline carrier build' success hybrid native64 ORES_NATIVE_IMAGE_OFFLINE=1 MOCK_EXPECT_OFFLINE=yes MOCK_EMBEDDED_MODE=hybrid MOCK_EMBEDDED_REFS=native64 MOCK_EXPECT_PROFILE_REFS=native64
assert_case 'invalid offline mode denied' failure aot compressed32 ORES_NATIVE_IMAGE_OFFLINE=sometimes
assert_case 'invalid mode' failure invalid compressed32
assert_case 'invalid refs' failure aot invalid
assert_case 'Maven failure' failure aot compressed32 MOCK_MVN_FAIL=1
assert_case 'script wrapper rather than native binary' failure aot compressed32 MOCK_BAD_BINARY=1
assert_case 'wrong baked reference mode' failure aot compressed32 MOCK_EMBEDDED_MODE=aot MOCK_EMBEDDED_REFS=native64
assert_case 'wrong baked image mode' failure aot compressed32 MOCK_EMBEDDED_MODE=hybrid
assert_case 'extra build-info lines' failure aot compressed32 MOCK_EXTRA_BUILD_INFO=1
assert_case 'source execution failure' failure aot compressed32 MOCK_BAD_PROGRAM_STATUS=1
assert_case 'check-only mistakenly executes guest' failure aot compressed32 MOCK_CHECK_EXECUTES_GUEST=1
assert_case 'silent callables guest execution' failure aot compressed32 MOCK_SILENT_CALLABLES=1
assert_case 'incorrect lexical closure result' failure aot compressed32 MOCK_BAD_CALLABLES=1
assert_case 'private actor capability violation admitted' failure aot compressed32 MOCK_ACCEPT_INVALID_PRIVATE_ACTOR=1
assert_case 'valid shared/private actor rejected' failure aot compressed32 MOCK_REJECT_VALID_ACTOR=1
assert_case 'actor rejected for unrelated parse error' failure aot compressed32 MOCK_WRONG_ACTOR_REJECTION=1
grep -Fx 'private actor negative fixture failed without a capability-denial diagnostic' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: unrelated parse error was not rejected at the actor capability gate' >&2
  exit 1
}
assert_case 'actor rejected for unrelated network capability' failure aot compressed32 MOCK_WRONG_ACTOR_CAPABILITY=1
grep -Fx 'private actor negative fixture failed without a capability-denial diagnostic' "$tmp/stderr" >/dev/null || {
  echo 'FAILED: unrelated network capability was not rejected at the actor capability gate' >&2
  exit 1
}
assert_case 'actor check-only emitted guest output' failure aot compressed32 MOCK_ACTOR_CHECK_EXECUTES_GUEST=1
assert_case 'silent fake image without guest-core output' failure aot compressed32 MOCK_EMPTY_CORE_OUTPUT=1
assert_case 'wrong native core numeric result' failure aot compressed32 MOCK_BAD_CORE_OUTPUT=1
assert_case 'silent fake image without guest-test output' failure aot compressed32 MOCK_EMPTY_TEST_OUTPUT=1
assert_case 'guest test reports failures' failure aot compressed32 MOCK_BAD_TEST_SUMMARY=1
assert_case 'valid summary without actual PASS/SKIP records' failure aot compressed32 MOCK_MISSING_TEST_EVENTS=1
assert_case 'duplicate valid summary' failure aot compressed32 MOCK_DUPLICATE_TEST_SUMMARY=1
assert_case 'valid plus conflicting summary' failure aot compressed32 MOCK_CONFLICTING_TEST_SUMMARY=1
assert_case 'valid summary alongside FAIL event' failure aot compressed32 MOCK_EXTRA_TEST_FAIL=1
assert_case 'valid summary alongside excess PASS event' failure aot compressed32 MOCK_EXTRA_TEST_PASS=1
assert_case 'duplicated PASS replacing missing PASS' failure aot compressed32 MOCK_REPLACE_FLOAT_WITH_ARITHMETIC_PASS=1
assert_case 'duplicate arithmetic PASS identity' failure aot compressed32 MOCK_DUPLICATE_TEST_PASS_ID=1
assert_case 'wrong SKIP test identity' failure aot compressed32 MOCK_WRONG_SKIP_ID=1
assert_case 'duplicate SKIP identity' failure aot compressed32 MOCK_DUPLICATE_TEST_SKIP_ID=1
assert_case 'native image accepting JVM JIT' failure aot compressed32 MOCK_JIT_STATUS=0
assert_case 'unexpected JIT refusal exit status' failure aot compressed32 MOCK_JIT_STATUS=1

echo 'MOCKED_NATIVE_GATE_CONTRACT_TESTS_PASSED (not a native-image proof)'

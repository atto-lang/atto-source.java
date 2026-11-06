#!/bin/sh
# Reproducible GraalVM Native Image build with executable, source-level smoke.
# Usage: sh scripts/build-and-verify-native-image.sh [aot|hybrid] [compressed32|native64]
set -eu

if [ "$#" -gt 2 ]; then
  echo "usage: $0 [aot|hybrid] [compressed32|native64]" >&2
  exit 2
fi

mode="${1:-aot}"
refs="${2:-compressed32}"

case "$mode" in
  aot) profile=native-aot; binary=ores-aot ;;
  hybrid) profile=native-hybrid; binary=ores-hybrid ;;
  *) echo "invalid image mode: $mode" >&2; exit 2 ;;
esac
case "$refs" in
  compressed32) ;;
  native64) profile="$profile,native-ref64" ;;
  *) echo "invalid reference mode: $refs" >&2; exit 2 ;;
esac

# Epsilon only disables collection; it does not reclaim Java-heap allocations.
# Never silently advertise Epsilon as proof of bounded-memory / zero allocation.
case "${ORES_NATIVE_GC_MODE:-serial}" in
  serial) ;;
  epsilon)
    if [ "$mode" != aot ]; then
      echo 'Epsilon GC is only admitted with full native AOT' >&2
      exit 2
    fi
    profile="$profile,native-epsilon"
    ;;
  *)
    echo 'ORES_NATIVE_GC_MODE must be serial or epsilon' >&2
    exit 2
    ;;
esac

# The Maven profile invokes JAVA_HOME/bin/native-image, not the JVM's javac.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/native-image" ]; then
  echo "JAVA_HOME must point to a GraalVM JDK with bin/native-image" >&2
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven (mvn) is required to build the native image" >&2
  exit 2
fi

"$JAVA_HOME/bin/native-image" --version

# Carrier CI prepares and verifies dependencies in an isolated, source-free
# step. When requested, prohibit Maven network dependency resolution while
# processing the private Oreslang checkout. Reject misspelled mode values.
case "${ORES_NATIVE_IMAGE_OFFLINE:-0}" in
  0) set -- ;;
  1) set -- -o ;;
  *) echo "ORES_NATIVE_IMAGE_OFFLINE must be 0 or 1" >&2; exit 2 ;;
esac
mvn -B -ntp "$@" -DskipTests "-P$profile" clean package

executable="./target/$binary"
if [ ! -s "$executable" ] || [ ! -x "$executable" ]; then
  echo "missing or non-executable native artifact: $executable" >&2
  exit 1
fi

# Catch wrappers or Java launch scripts being mistaken for Native Image outputs.
# Do not downgrade verification when the OS file classifier is missing.
if ! command -v file >/dev/null 2>&1; then
  echo "file command required for native executable verification" >&2
  exit 2
fi

description="$(file "$executable")"
printf '%s\n' "$description"
case "$description" in
  *ELF*|*Mach-O*|*PE32*) ;;
  *) echo "artifact is not an OS-native executable" >&2; exit 1 ;;
esac

# Preserve raw stdout/stderr: command substitution discards trailing newlines,
# allowing extra blank output and stderr warnings to masquerade as a clean manifest.
build_info_stdout="$(mktemp)"
# Install cleanup after the first successful allocation. A later mktemp
# failure triggers set -e before the previous late trap could be registered.
# One trap covers every stage, including partially allocated later stages.
cleanup_native_image_verifier() {
  for verifier_tmp in \
    "$build_info_stdout" \
    "${build_info_stderr:-}" \
    "${build_info_expected:-}" \
    "${guest_stderr:-}" \
    "${guest_stdout:-}" \
    "${guest_exact_actual:-}" \
    "${guest_expected:-}" \
    "${test_output_file:-}"; do
    if [ -n "$verifier_tmp" ]; then
      rm -f "$verifier_tmp"
    fi
  done
}
# POSIX sh does not run EXIT traps for uncaught termination signals
# (notably dash on Linux). Convert signals into explicit exits so that
# partially allocated manifest, guest, and summary files are reclaimed.
# Preserve conventional shell signal exit statuses for CI cancellation.
trap 'cleanup_native_image_verifier' 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
build_info_stderr="$(mktemp)"
build_info_expected="$(mktemp)"
build_info_status=0
"$executable" --build-info >"$build_info_stdout" 2>"$build_info_stderr" || build_info_status=$?
if [ "$build_info_status" -ne 0 ] || [ -s "$build_info_stderr" ]; then
  echo "native --build-info failed or emitted stderr diagnostics" >&2
  if [ -s "$build_info_stderr" ]; then cat "$build_info_stderr" >&2; fi
  exit 1
fi
# Requiring the complete, canonical manifest prevents substring or warning-line
# matches from certifying a wrong build mode/reference encoding.
case "$mode" in
  aot) guest_execution=interpreter-no-jit ;;
  hybrid) guest_execution=runtime-selected ;;
esac
expected="{\"build_mode\":\"$mode\",\"default_execution_mode\":\"$mode\",\"managed_reference_mode\":\"$refs\",\"guest_execution\":\"$guest_execution\"}"
printf '%s' "$expected" >"$build_info_expected"
if ! cmp -s "$build_info_stdout" "$build_info_expected"; then
  printf '\n' >>"$build_info_expected"
  if ! cmp -s "$build_info_stdout" "$build_info_expected"; then
    echo "native build-info does not match canonical $mode/$refs manifest" >&2
    exit 1
  fi
fi
cat "$build_info_stdout"

# Byte-level provenance for human/machine native test output. AWK's handling
# of NUL/CR/terminal controls is implementation-dependent; forbid every byte
# except printable ASCII and LF in the known machine-readable records.
native_text_record_clean_bytes() {
  LC_ALL=C tr -cd '\12\40-\176' < "$1" | cmp -s - "$1"
}

# Native guest smokes must have clean stderr as well as the expected stdout.
# A zero exit plus correct stdout must not hide a runtime diagnostic on stderr.
# The file is isolated per invocation; it is removed when the verifier exits.
guest_stderr="$(mktemp)"
guest_stdout="$(mktemp)"
guest_exact_actual="$(mktemp)"
guest_expected="$(mktemp)"
run_clean_guest() {
  : > "$guest_stderr"
  : > "$guest_stdout"
  guest_status=0
  (
    unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
    "$executable" "$@" >"$guest_stdout" 2>"$guest_stderr"
  ) || guest_status=$?
  if [ "$guest_status" -ne 0 ]; then
    if [ -s "$guest_stderr" ]; then cat "$guest_stderr" >&2; fi
    return "$guest_status"
  fi
  if [ -s "$guest_stderr" ]; then
    echo 'native guest unexpectedly emitted stderr diagnostics' >&2
    cat "$guest_stderr" >&2
    return 1
  fi
  cat "$guest_stdout"
}

# Compare raw bytes, allowing either no final LF or exactly one final LF.
# Shell command substitution strips ALL trailing newlines, potentially hiding
# extra blank stdout records from otherwise successful native guest smokes.
run_exact_guest() {
  expected_exact_output="$1"
  exact_diagnostic="$2"
  shift 2
  run_clean_guest "$@" > "$guest_exact_actual" || return $?
  printf '%s' "$expected_exact_output" > "$guest_expected"
  if cmp -s "$guest_exact_actual" "$guest_expected"; then
    cat "$guest_exact_actual"
    return 0
  fi
  printf '\n' >> "$guest_expected"
  if cmp -s "$guest_exact_actual" "$guest_expected"; then
    cat "$guest_exact_actual"
    return 0
  fi
  echo "$exact_diagnostic" >&2
  return 1
}

# Exercise a REAL .ores file and packaged stdlib with Java build environment
# variables removed. Only a successful native build plus execution passes.
# Admission must NEVER execute the guest entry point. The same callables
# fixture prints 120:9:13 when executed, but --check must emit no output
# on either stdout or stderr.
: > "$guest_stdout"
: > "$guest_stderr"
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/callables.ores >"$guest_stdout" 2>"$guest_stderr"
) || exit $?
# Inspect raw bytes: command substitution discards trailing LF-only output.
if [ -s "$guest_stdout" ] || [ -s "$guest_stderr" ]; then
  echo "native --check unexpectedly emitted diagnostics or guest output" >&2
  exit 1
fi

# Running the checked source must execute its main routine. In addition to
# the stdlib test below, verify factorial, lexical and nlexical closures.
run_exact_guest '120:9:13' 'native callables guest output differs from expected value' "--mode=$mode" examples/callables.ores

# Validate the linked actor-isolation admission boundary inside the *native*
# executable as well. Positive shared/private declarations must typecheck,
# while private actors must not launder SharedMutex/share_readonly authority
# through imported types, helpers, or callbacks.
: > "$guest_stdout"
: > "$guest_stderr"
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/shared-private-actor-isolation.ores >"$guest_stdout" 2>"$guest_stderr"
) || exit $?
if [ -s "$guest_stdout" ] || [ -s "$guest_stderr" ]; then
  echo "native actor --check unexpectedly emitted diagnostics or guest output" >&2
  exit 1
fi
if [ ! -f examples/private-actor-sharing-invalid.ores ]; then
  echo "missing private actor capability-invalid fixture" >&2
  exit 1
fi
# A parse error, missing import, or crash is NOT proof of private-actor
# isolation. Only the expected capability-denial diagnostic establishes that
# the linked checker reached and enforced the protected actor boundary.
# OresMain returns status 1 for rejected --check admission. A crash
# (signal status) or CLI usage failure (2) is not a capability-denial proof.
: > "$guest_stdout"
: > "$guest_stderr"
invalid_actor_status=0
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --check examples/private-actor-sharing-invalid.ores >"$guest_stdout" 2>"$guest_stderr"
) || invalid_actor_status=$?
if [ "$invalid_actor_status" -ne 1 ]; then
  echo "expected private actor capability denial with CLI status 1, got $invalid_actor_status" >&2
  exit 1
fi
# A rejection message on stdout, a capability phrase embedded in a parse
# diagnostic, or a correct-looking line alongside an unrelated diagnostic is
# not proof of linked actor admission. Enforce one stderr record, anchored at
# its beginning, so grep cannot find an isolated matching line in a traceback.
if [ -s "$guest_stdout" ] ||
   ! native_text_record_clean_bytes "$guest_stderr" ||
   ! LC_ALL=C awk '
     NR != 1 { bad=1; next }
     $0 !~ /^Oreslang isolate denies capability (SHARED_MEMORY|ACTOR_SHARE_READONLY)([[:space:]:]|$)/ { bad=1 }
     END { if (NR != 1 || bad) exit 1 }
   ' "$guest_stderr"; then
  echo "private actor negative fixture failed without a capability-denial diagnostic" >&2
  exit 1
fi

run_clean_guest "--mode=$mode" examples/together.ores

# Verify exact Oreslang semantics and packaged stdlib resolution, not merely
# a successful process exit. This output is from the checked-in native core
# library fixture (also observed in the previously successful AOT CI run).
run_exact_guest '9007199254740992|-999999999999999999|10' 'native Oreslang core-library output differs from expected value' "--mode=$mode" examples/native-core-library.ores

# Native channel selection must preserve ordered buffered reads and return
# the unselected message. A wrong select winner or a lost second message
# cannot be certified by a zero guest exit status.
run_exact_guest 'first:7:9' 'native channel/select result differs from expected first-winner and retained-message output' "--mode=$mode" examples/native-channel-select-smoke.ores

# Verify that select wakes for completed Future<T> when the channel is
# intentionally empty. Do not mistake silent completion for async progress.
run_exact_guest 'future:4' 'native Future/select output differs from expected result' "--mode=$mode" examples/native-future-select-smoke.ores

# Root await must actually resume and return the typed result under both
# Native Image AOT and hybrid, not merely pass linked syntax checking.
run_exact_guest '42' 'native root-await result differs from expected value' "--mode=$mode" examples/strict-root-await.ores

# A native image that merely exits zero without executing guest code is NOT
# sufficient evidence. Require the real Oreslang test framework's known output.
# Capture raw stdout in a file (no pipeline can mask the guest exit status).
# Preserve the raw stdout file byte-for-byte. POSIX command substitution
# trims trailing newlines, which could hide an extra blank record after an
# otherwise correct test summary and falsely certify the native executable.
test_output_file="$(mktemp)"
# The initial cleanup trap also owns this final output file.
: > "$guest_stderr"
test_guest_status=0
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" "--mode=$mode" examples/native-testing.ores >"$test_output_file" 2>"$guest_stderr"
) || test_guest_status=$?
if [ "$test_guest_status" -ne 0 ]; then
  if [ -s "$guest_stderr" ]; then cat "$guest_stderr" >&2; fi
  echo "native guest test failed with exit status $test_guest_status" >&2
  exit "$test_guest_status"
fi
if [ -s "$guest_stderr" ]; then
  echo 'native guest unexpectedly emitted stderr diagnostics' >&2
  cat "$guest_stderr" >&2
  exit 1
fi
cat "$test_output_file"
expected_summary='ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0'
# Require each exact guest test identity once; counts alone can be forged by
# duplicating one PASS event while omitting another. The checked-in fixture
# requests machine-readable records, so *all* stdout must be ORES_TEST events;
# stray logs/warnings/diagnostics must not hide behind a valid summary.
# Tolerate detail text only after a recognized test name. The summary is
# terminal: accepting PASS/SKIP events after SUMMARY can falsely certify an
# incomplete test run whose supposedly final summary was emitted too early.
if ! native_text_record_clean_bytes "$test_output_file" ||
   ! LC_ALL=C awk -F '|' -v expected="$expected_summary" '
  !/^ORES_TEST\|/ { bad=1; next }
  /^ORES_TEST\|/ {
    if ($0 == expected) { summaries++; next }
    if (summaries) { bad=1; next }
    if ($1 != "ORES_TEST" || NF != 4) { bad=1; next }
    if ($2 == "PASS" && $3 == "core/arithmetic/adds") { adds++; next }
    if ($2 == "PASS" && $3 == "core/float/tolerance") { floats++; next }
    if ($2 == "PASS" && $3 == "core/property/square") { properties++; next }
    if ($2 == "SKIP" && $3 == "core/future/parallel-runner") { skips++; next }
    bad=1
  }
  END {
    if (bad || summaries != 1 || adds != 1 || floats != 1 ||
        properties != 1 || skips != 1) exit 1
  }
' "$test_output_file"; then
  echo "native executable did not produce the exact unique PASS/SKIP guest tests and summary" >&2
  exit 1
fi

# A native executable must never silently switch on JVM JIT at runtime.
: > "$guest_stdout"
: > "$guest_stderr"
jit_status=0
(
  unset JAVA_HOME CLASSPATH JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
  "$executable" --mode=jit --check examples/callables.ores >"$guest_stdout" 2>"$guest_stderr"
) || jit_status=$?
if [ "$jit_status" -ne 2 ]; then
  echo "expected --mode=jit to be rejected with CLI status 2, got $jit_status" >&2
  exit 1
fi
case "$mode" in
  aot) expected_jit_line="oreslang: This executable was built AOT-only; --mode=jit cannot enable runtime compilation. Rebuild with native-hybrid." ;;
  hybrid) expected_jit_line="oreslang: Native hybrid executables accept --mode=hybrid or --mode=aot, not JVM --mode=jit" ;;
esac
# Require the complete single diagnostic, not a prefix followed by unrelated
# fatal text. Also reject NUL/CR/control bytes that text-line AWK can ignore.
if [ -s "$guest_stdout" ] ||
   ! native_text_record_clean_bytes "$guest_stderr" ||
   ! LC_ALL=C awk -v expected="$expected_jit_line" '
     NR != 1 { bad=1; next }
     $0 != expected { bad=1 }
     END { if (NR != 1 || bad) exit 1 }
   ' "$guest_stderr"; then
  echo "native executable refused JIT without the required build-mode diagnostic" >&2
  exit 1
fi

echo "verified GraalVM Native Image: $executable ($mode, $refs)"

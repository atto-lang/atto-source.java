#!/usr/bin/env bash
# Restricted .atto completed-Future pipeline -> generated Java / Graal Native Image.
set -euo pipefail
cd "$(dirname "$0")/../.."
bash ci/admission/exact-tree.sh \
  src/compiler/asyncv1/AttoAsyncV1Compiler.java \
  tests/compiler/AttoAsyncV1CompilerProbe.java \
  tests/compiler/AttoAsyncV1CliSecurityProbe.java \
  tests/compiler/AttoAsyncV1GeneratedIdentifierProbe.java \
  fixtures/async_v1/completed_future.atto \
  fixtures/async_v1/completed_future_typed.atto \
  fixtures/async_v1/sync_inline_int.atto \
  fixtures/async_v1/sync_inline_empty.atto \
  fixtures/async_v1/sync_inline_do_nonvoid.atto \
  fixtures/async_v1/sync_inline_bare_without_do.atto \
  fixtures/async_v1/sync_inline_bare_typed_void.atto \
  ci/async/verify-compiled-completed-future.sh
test -x "$JAVA_HOME/bin/javac" && test -x "$JAVA_HOME/bin/native-image" || exit 2
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/classes" \
  src/compiler/asyncv1/AttoAsyncV1Compiler.java \
  tests/compiler/AttoAsyncV1CompilerProbe.java \
  tests/compiler/AttoAsyncV1CliSecurityProbe.java \
  tests/compiler/AttoAsyncV1GeneratedIdentifierProbe.java
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1CompilerProbe | tee "$work/probe"
grep -Fx "ATTO_ASYNC_V1_ADMISSION_PASS checks=97" "$work/probe"
"$JAVA_HOME/bin/java" -cp "$work/classes" \
  atto.compiler.asyncv1.AttoAsyncV1CliSecurityProbe | tee "$work/cli-security"
grep -Fx "ATTO_ASYNC_V1_CLI_SECURITY_PASS checks=10" "$work/cli-security"
"$JAVA_HOME/bin/java" -cp "$work/classes" \
  atto.compiler.asyncv1.AttoAsyncV1GeneratedIdentifierProbe | tee "$work/identifiers"
grep -Fx "ATTO_ASYNC_V1_GENERATED_NAMES_PASS accepted=19 rejected=4" "$work/identifiers"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/completed_future.atto "$work/AttoAsyncV1Guest.java"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/classes" "$work/AttoAsyncV1Guest.java"
set +e
"$JAVA_HOME/bin/java" -cp "$work/classes" AttoAsyncV1Guest >"$work/jvm.out" 2>"$work/jvm.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/jvm.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/jvm.out"
(
  cd "$work"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
    -H:Name=atto-async-v1 -cp "$work/classes" AttoAsyncV1Guest
)
test -x "$work/atto-async-v1"
file "$work/atto-async-v1" | grep -Eq 'ELF|Mach-O'
set +e
timeout 60 "$work/atto-async-v1" >"$work/native.out" 2>"$work/native.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/native.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/native.out"
grep -Fx 'ATTO_ASYNC_SCOPE=COMPLETED_FUTURE_ONLY' "$work/native.out"
test ! -s "$work/native.err"
# Canonical syntax task<int> produces Future<int> in this closed-world,
# already-completed scalar source profile. This is not cancellation support.
mkdir -p "$work/typed/classes"
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/completed_future_typed.atto "$work/typed/AttoAsyncV1Guest.java"
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/typed/classes" \
  "$work/typed/AttoAsyncV1Guest.java"
set +e
"$JAVA_HOME/bin/java" -cp "$work/typed/classes" AttoAsyncV1Guest \
  >"$work/typed/jvm.out" 2>"$work/typed/jvm.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/typed/jvm.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/typed/jvm.out"
(
  cd "$work/typed"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
    -H:Name=atto-async-v1-typed -cp "$work/typed/classes" AttoAsyncV1Guest
)
test -x "$work/typed/atto-async-v1-typed"
file "$work/typed/atto-async-v1-typed" | grep -Eq 'ELF|Mach-O'
set +e
timeout 60 "$work/typed/atto-async-v1-typed" \
  >"$work/typed/native.out" 2>"$work/typed/native.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/typed/native.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/typed/native.out"
test ! -s "$work/typed/native.err"
echo 'ATTO_ASYNC_V1_TYPED_PAYLOAD_JVM_NATIVE=PASS'
echo 'ATTO_ASYNC_V1_JVM_AND_NATIVE=PASS'
echo 'ATTO_GENERAL_FUTURE_SUSPENSION_AND_BORROW_CHECKER=NOT_VERIFIED'

# Verify lexically inline source-to-Java execution and no TaskSpawn/Future.
for case in sync_inline_int sync_inline_empty; do
  mkdir -p "$work/$case/classes"
  "$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
    "fixtures/async_v1/$case.atto" "$work/$case/AttoAsyncV1Guest.java"
  if grep -Eq 'CompletableFuture|\.join\(' "$work/$case/AttoAsyncV1Guest.java"; then
    echo "inline task unexpectedly allocated a Future or joined" >&2
    exit 1
  fi
  "$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$work/$case/classes" \
    "$work/$case/AttoAsyncV1Guest.java"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/$case/classes" AttoAsyncV1Guest \
    >"$work/$case/jvm.out" 2>"$work/$case/jvm.err"
  status=$?
  set -e
  test "$status" -eq 42 || { cat "$work/$case/jvm.err"; exit 1; }
  grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/$case/jvm.out"
  grep -Fx 'ATTO_ASYNC_SCOPE=PURE_INLINE_TASK_ONLY' "$work/$case/jvm.out"
  test ! -s "$work/$case/jvm.err"
done
(
  cd "$work/sync_inline_int"
  "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
    -H:Name=atto-sync-inline-v1 -cp "$work/sync_inline_int/classes" AttoAsyncV1Guest
)
test -x "$work/sync_inline_int/atto-sync-inline-v1"
set +e
timeout 60 "$work/sync_inline_int/atto-sync-inline-v1" \
  >"$work/sync_inline_int/native.out" 2>"$work/sync_inline_int/native.err"
status=$?
set -e
test "$status" -eq 42 || { cat "$work/sync_inline_int/native.err"; exit 1; }
grep -Fx 'ATTO_ASYNC_V1_RESULT=42' "$work/sync_inline_int/native.out"
grep -Fx 'ATTO_ASYNC_SCOPE=PURE_INLINE_TASK_ONLY' "$work/sync_inline_int/native.out"
test ! -s "$work/sync_inline_int/native.err"
echo 'ATTO_ASYNC_V1_PURE_INLINE_JVM_NATIVE=PASS'

# Invalid discarded task payloads must fail before creating generated Java.
mkdir -p "$work/do-sync-negative"
set +e
"$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
  fixtures/async_v1/sync_inline_do_nonvoid.atto \
  "$work/do-sync-negative/AttoAsyncV1Guest.java" \
  >"$work/do-sync-negative/out" 2>"$work/do-sync-negative/err"
status=$?
set -e
test "$status" -ne 0
grep -F 'E_TASK_RETURN_TYPE' "$work/do-sync-negative/err"
test ! -e "$work/do-sync-negative/AttoAsyncV1Guest.java"
echo 'ATTO_DO_SYNC_VOID_ONLY_NO_ARTIFACT=PASS'

# Bare sync task expressions are forbidden as standalone statements. Both
# invalid syntax forms must emit E_SYNTAX and no Java guest source artifact.
for case in sync_inline_bare_without_do sync_inline_bare_typed_void; do
  target="$work/$case/AttoAsyncV1Guest.java"
  mkdir -p "$work/$case"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/classes" atto.compiler.asyncv1.AttoAsyncV1Compiler \
    "fixtures/async_v1/$case.atto" "$target" \
    >"$work/$case/out" 2>"$work/$case/err"
  status=$?
  set -e
  test "$status" -ne 0
  grep -F 'E_SYNTAX' "$work/$case/err"
  test ! -e "$target" || { echo "ATTO_BARE_SYNC_GUEST_ARTIFACT_LEAK=$case"; exit 1; }
done
echo 'ATTO_SYNC_STANDALONE_REQUIRES_DO_NO_ARTIFACT=PASS'

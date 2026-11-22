#!/usr/bin/env bash
# Source SHA-pinned supplementary Atto edges: actual JVM + native-image guests.
# Invoked after the parent script compiled AttoFunctionalPipelineCompiler.
set -euo pipefail
work="$1"
compiler_main=atto.compiler.pipeline.AttoFunctionalPipelineCompiler
edge=fixtures/pipelines/edge
mkdir -p "$work/edge"
while IFS=: read -r name sha; do
  actual="$(git hash-object "$edge/$name.atto")"
  test "$actual" = "$sha" || {
    echo "ATTO_JAVA_EDGE_SHA_DRIFT file=$name want=$sha got=$actual" >&2; exit 2;
  }
done <<'PINNED'
two-argument-61:4089c524db1d076593994ad65acb5ca7f90eea32
preapplied-grouped-42:9a89ada18865fead0d92a7de75415957fbb5f67f
signed-i64-min:bbc22347d7ccb0a5be1a7c2193f11c5f2f4805cc
checked-overflow:59bb890b54addc1659065ab55cd565771523bfce
reject-i64-positive-overflow:2e584ee01ee74a3a31e282cf0116957f9ff95bd1
reject-i64-negative-underflow:8b63b45e406db3e19aa793e0f7a627914623b9e0
reject-reserved-unsafe:b159def3fa26ca6023395e1c546b95c1b929fd14
PINNED

compile_edge() {
  local name="$1" dest="$work/edge/$1"
  mkdir -p "$dest"
  "$JAVA_HOME/bin/java" -cp "$work/compiler" "$compiler_main" \
    "$edge/$name.atto" "$dest/AttoFunctionalPipelineGuest.java" \
    >"$dest/compiler.stdout" 2>"$dest/compiler.stderr"
  test -s "$dest/AttoFunctionalPipelineGuest.java"
  test ! -s "$dest/compiler.stderr"
  "$JAVA_HOME/bin/javac" -Xlint:all -Werror -d "$dest" \
    "$dest/AttoFunctionalPipelineGuest.java"
}
for case in two-argument-61:61 preapplied-grouped-42:42; do
  IFS=: read -r name expected <<<"$case"
  compile_edge "$name"
  dest="$work/edge/$name"
  set +e
  "$JAVA_HOME/bin/java" -cp "$dest" AttoFunctionalPipelineGuest \
    >"$dest/jvm.stdout" 2>"$dest/jvm.stderr"
  status=$?
  set -e
  test "$status" -eq "$expected" && test ! -s "$dest/jvm.stdout" \
    && test ! -s "$dest/jvm.stderr" || {
    echo "ATTO_JAVA_EDGE_JVM_FAILURE name=$name exit=$status" >&2; exit 1;
  }
  # Guest code (not the host test) is compiled into a native image.
  (
    cd "$dest"
    "$JAVA_HOME/bin/native-image" --no-fallback --gc=epsilon \
      -H:Name=atto-edge -cp "$dest" AttoFunctionalPipelineGuest
  )
  test -x "$dest/atto-edge"
  set +e
  "$dest/atto-edge" >"$dest/native.stdout" 2>"$dest/native.stderr"
  status=$?
  set -e
  test "$status" -eq "$expected" && test ! -s "$dest/native.stdout" \
    && test ! -s "$dest/native.stderr" || {
    echo "ATTO_JAVA_EDGE_NATIVE_FAILURE name=$name exit=$status" >&2; exit 1;
  }
  echo "ATTO_JAVA_EDGE_COMPILED_GUEST=PASS name=$name main=$expected"
done

# Observe the full signed-64-bit guest result without truncating it to OS
# process status. The observer only calls the compiled guest method.
compile_edge signed-i64-min
min_dir="$work/edge/signed-i64-min"
cat >"$min_dir/AttoEdgeMinObserver.java" <<'JAVA'
public final class AttoEdgeMinObserver {
  public static void main(String[] args) {
    System.out.println(AttoFunctionalPipelineGuest.f_main());
  }
}
JAVA
"$JAVA_HOME/bin/javac" -Xlint:all -Werror -cp "$min_dir" \
  -d "$min_dir" "$min_dir/AttoEdgeMinObserver.java"
observed="$("$JAVA_HOME/bin/java" -cp "$min_dir" AttoEdgeMinObserver)"
test "$observed" = "-9223372036854775808" || {
  echo "ATTO_JAVA_EDGE_MIN_WRONG_VALUE observed=$observed" >&2; exit 1;
}
echo "ATTO_JAVA_EDGE_I64_MIN_JVM=PASS"

compile_edge checked-overflow
overflow_dir="$work/edge/checked-overflow"
set +e
"$JAVA_HOME/bin/java" -cp "$overflow_dir" AttoFunctionalPipelineGuest \
  >"$overflow_dir/stdout" 2>"$overflow_dir/stderr"
status=$?
set -e
test "$status" -ne 0 && test ! -s "$overflow_dir/stdout"
grep -Fq 'ArithmeticException' "$overflow_dir/stderr" || {
  echo "ATTO_JAVA_EDGE_OVERFLOW_WAS_NOT_CHECKED" >&2; exit 1;
}
echo "ATTO_JAVA_EDGE_CHECKED_OVERFLOW=PASS"

for case in \
  reject-i64-positive-overflow:E_LITERAL \
  reject-i64-negative-underflow:E_LITERAL \
  reject-reserved-unsafe:E_NAME; do
  IFS=: read -r name expected <<<"$case"
  dest="$work/edge/$name"
  mkdir -p "$dest"
  set +e
  "$JAVA_HOME/bin/java" -cp "$work/compiler" "$compiler_main" \
    "$edge/$name.atto" "$dest/AttoFunctionalPipelineGuest.java" \
    >"$dest/stdout" 2>"$dest/stderr"
  status=$?
  set -e
  test "$status" -eq 2 && test ! -e "$dest/AttoFunctionalPipelineGuest.java" \
    && test ! -s "$dest/stdout" && grep -Fq "$expected:" "$dest/stderr" || {
    echo "ATTO_JAVA_EDGE_NEGATIVE_LEAK name=$name exit=$status" >&2; exit 1;
  }
done
echo 'ATTO_JAVA_EDGE_MATRIX=JVM_3_NATIVE_IMAGE_2_OVERFLOW_1_NEGATIVE_3'


# Every scalar frontend must reject reserved task/synchronization/generator
# identifiers in all binding positions, without generating Java guest code.
for name in sync task yield; do
  for site in function parameter local; do
    dest="$work/edge/reserved-${name}-${site}"
    mkdir -p "$dest"
    source="$dest/input.atto"
    case "$site" in
      function)
        printf 'fnc %s(int x): int -> x; pub fnc main(): int -> 42 |> %s;\n' "$name" "$name" >"$source" ;;
      parameter)
        printf 'fnc f(int %s): int -> 42; pub fnc main(): int -> f(1);\n' "$name" >"$source" ;;
      local)
        printf 'pub fnc main(): int { val %s = 42; return %s; }\n' "$name" "$name" >"$source" ;;
    esac
    set +e
    "$JAVA_HOME/bin/java" -cp "$work/compiler" "$compiler_main" \
      "$source" "$dest/AttoFunctionalPipelineGuest.java" \
      >"$dest/stdout" 2>"$dest/stderr"
    status=$?
    set -e
    test "$status" -eq 2 && test ! -e "$dest/AttoFunctionalPipelineGuest.java" \
      && test ! -s "$dest/stdout" && grep -Fq 'E_NAME:' "$dest/stderr" || {
      echo "ATTO_JAVA_RESERVED_ADMISSION_FAIL name=$name site=$site exit=$status" >&2
      exit 1
    }
  done
done
echo 'ATTO_JAVA_AUTHORITY_WORD_MATRIX=9_NO_GUEST_OUTPUT'

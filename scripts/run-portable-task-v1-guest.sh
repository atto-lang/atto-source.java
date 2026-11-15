#!/usr/bin/env bash
# Candidate exact-source .atto Task guest test runner. No cross-org checkout or mirror.
# Existing local compiler and explicitly supplied shared conformance checkout only.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
: "${ATTO_TASK_SUITE_ROOT:?Provide an authorized, source-local shared conformance checkout}"
: "${ATTO_TASK_SUITE_SHA:?Pin the exact conformance Git HEAD (40 hex chars)}"
[[ "$ATTO_TASK_SUITE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid suite SHA' >&2; exit 64; }
suite="$(cd -- "$ATTO_TASK_SUITE_ROOT" && pwd -P)"
[[ -f "$suite/bin/portable-task-v1-run.mjs" ]] || { echo 'Shared .atto runner missing' >&2; exit 69; }
[[ "$(git -C "$suite" rev-parse HEAD)" == "$ATTO_TASK_SUITE_SHA" ]] || { echo 'Suite HEAD mismatch' >&2; exit 78; }
[[ "$(git -C "$root" rev-parse HEAD)" =~ ^[0-9a-f]{40}$ ]] || exit 78
command -v node >/dev/null || { echo 'Node >=22 required' >&2; exit 69; }
# Direct JDK 25 JVM launcher; never call a Python probe.
java_bin="$(command -v java)" || { echo 'JDK 25 Java launcher missing' >&2; exit 69; }
[[ "$java_bin" == /* ]] || { echo 'absolute JVM executable required' >&2; exit 69; }
[[ -f "$root/target/classes/dev/oreslang/launcher/OresMain.class" ]] || { echo 'Build JVM guest launcher first' >&2; exit 69; }
[[ -s "$root/target/conformance-classpath.txt" ]] || { echo 'Resolve Maven dependencies before guest execution' >&2; exit 69; }
deps="$(cat "$root/target/conformance-classpath.txt")"
[[ "$deps" != *$'\n'* && "$deps" != *$'\r'* ]] || { echo 'Malformed Maven classpath' >&2; exit 78; }

config="$(mktemp "${TMPDIR:-/tmp}/atto-task-java.XXXXXXXX")"
trap 'rm -f -- "$config"' EXIT
export ATTO_RUNNER_COMPILER_SHA="$(git -C "$root" rev-parse HEAD)"
export ATTO_RUNNER_ROOT="$root" ATTO_RUNNER_SUITE_SHA="$ATTO_TASK_SUITE_SHA"
export ATTO_RUNNER_CONFIG="$config" ATTO_RUNNER_BACKEND="java"
export ATTO_RUNNER_JAVA="$java_bin"
export ATTO_RUNNER_CLASSPATH="$root/target/classes:$deps"
node --input-type=module -e '
  import {writeFileSync} from "node:fs";
  const e=process.env,root=e.ATTO_RUNNER_ROOT;
  const javaArgs=[e.ATTO_RUNNER_JAVA,
    "-XX:+UnlockExperimentalVMOptions","-XX:+EnableJVMCI",
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
    "-cp",e.ATTO_RUNNER_CLASSPATH,"dev.oreslang.launcher.OresMain"];
  const identity=root+"/target/classes/dev/oreslang/launcher/OresMain.class";
  const cfg={backend:"java",compilerRoot:root,
    compilerCommit:e.ATTO_RUNNER_COMPILER_SHA,fixtureCommit:e.ATTO_RUNNER_SUITE_SHA,
    compilerIdentity:identity,artifactPath:identity,
    compileArgv:[...javaArgs,"--check","{file}"],
    guestArgv:[...javaArgs,"{file}"]};
  writeFileSync(e.ATTO_RUNNER_CONFIG,JSON.stringify(cfg)+"\n",{mode:0o600});
'
node "$suite/bin/portable-task-v1-run.mjs" "--config=$config"

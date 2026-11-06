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
# Reuse the existing Java/GraalVM guest probe; no new Python implementation.
python_bin="$(command -v python3)" || { echo 'Existing Python guest probe dependency missing' >&2; exit 69; }
[[ -f "$root/contracts/v1/java_probe.py" && -f "$root/target/classes/dev/oreslang/launcher/OresMain.class" ]] || { echo 'Build Java launcher first: mvn package and dependency:build-classpath' >&2; exit 69; }
[[ -s "$root/target/conformance-classpath.txt" ]] || { echo 'Resolve Java classpath before guest execution' >&2; exit 69; }

config="$(mktemp "${TMPDIR:-/tmp}/atto-task-java.XXXXXXXX.json")"
trap 'rm -f -- "$config"' EXIT
export ATTO_RUNNER_COMPILER_SHA="$(git -C "$root" rev-parse HEAD)"
export ATTO_RUNNER_ROOT="$root" ATTO_RUNNER_SUITE_SHA="$ATTO_TASK_SUITE_SHA"
export ATTO_RUNNER_CONFIG="$config" ATTO_RUNNER_BACKEND="java"
export ATTO_RUNNER_PYTHON="$python_bin"
node --input-type=module -e '
  import {writeFileSync} from "node:fs";
  const e=process.env,root=e.ATTO_RUNNER_ROOT;
  const isJava=e.ATTO_RUNNER_BACKEND==="java";
  const probe=root+"/contracts/v1/java_probe.py";
  const identity=isJava?root+"/target/classes/dev/oreslang/launcher/OresMain.class":root+"/build/oreslang-llvmc";
  const cfg={
    backend:e.ATTO_RUNNER_BACKEND,compilerRoot:root,
    compilerCommit:e.ATTO_RUNNER_COMPILER_SHA,fixtureCommit:e.ATTO_RUNNER_SUITE_SHA,
    compilerIdentity:identity,artifactPath:isJava?identity:"{out}",
    compileArgv:isJava?[e.ATTO_RUNNER_PYTHON,probe,"{file}"]:[root+"/scripts/compile-aot.sh","{file}","{out}"],
    guestArgv:isJava?[e.ATTO_RUNNER_PYTHON,probe,"{file}","--execute"]:["{out}"]
  };
  writeFileSync(e.ATTO_RUNNER_CONFIG,JSON.stringify(cfg)+"\n",{mode:0o600});
'
node "$suite/bin/portable-task-v1-run.mjs" "--config=$config"

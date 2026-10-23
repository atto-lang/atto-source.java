#!/usr/bin/env bash
# Execute a single real Oreslang guest source through the Java/GraalVM CLI.
# Requires previously built, pinned target/classes and Maven dependency classpath.
set -euo pipefail
if (( $# != 1 )); then
  echo "usage: $0 SOURCE.ores" >&2
  exit 64
fi
source_file="$1"
if [[ "$source_file" != *.ores || ! -f "$source_file" ]]; then
  echo "expected a readable .ores source file" >&2
  exit 66
fi
root="$(cd "$(dirname "$0")/.." && pwd -P)"
classes="$root/target/classes"
classpath_file="$root/target/conformance-classpath.txt"
if [[ ! -d "$classes" || ! -s "$classpath_file" ]]; then
  echo "Java runtime not built; run 'mvn -B -ntp -DskipTests package' and 'mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/conformance-classpath.txt' first" >&2
  exit 69
fi
classpath="$(cat "$classpath_file")"
exec java -cp "$classes:$classpath" dev.oreslang.launcher.OresMain --no-core-log "$source_file"

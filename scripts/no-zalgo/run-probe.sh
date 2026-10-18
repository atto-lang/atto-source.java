#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
if [[ ! -f "$repo/target/no-zalgo-classpath.txt" ]]; then
  echo 'Run mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/no-zalgo-classpath.txt first' >&2
  exit 2
fi
exec java --enable-native-access=ALL-UNNAMED -Dores.runtime.carriers="${ORES_EVENT_CARRIERS:-native}" -Djava.library.path="$repo/target/native" -cp "$repo/target/test-classes:$repo/target/classes:$(cat "$repo/target/no-zalgo-classpath.txt")" dev.oreslang.runtime.EventDispatchProbeTest

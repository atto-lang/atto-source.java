#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != Linux ]]; then
  echo "measure-dop-cache.sh requires Linux perf" >&2
  exit 2
fi
if ! command -v perf >/dev/null 2>&1; then
  echo "perf is not installed" >&2
  exit 2
fi

rows="${ORES_BENCH_ROWS:-1024,16384,131072}"
iterations="${ORES_BENCH_ITERATIONS:-200}"
warmup="${ORES_BENCH_WARMUP:-50}"
samples="${ORES_BENCH_SAMPLES:-5}"
cpu="${ORES_BENCH_CPU:-}"
events="${ORES_PERF_EVENTS:-task-clock,cycles,instructions,L1-dcache-loads,L1-dcache-load-misses,cache-references,cache-misses,branch-instructions,branch-misses,cpu-migrations,context-switches,page-faults}"

mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/dop-bench.classpath
classpath="target/test-classes:target/classes:$(cat target/dop-bench.classpath)"

runner=(java -ea -cp "$classpath" dev.oreslang.runtime.ActorShardLayoutBench)
if [[ -n "$cpu" ]]; then
  if ! command -v taskset >/dev/null 2>&1; then
    echo "ORES_BENCH_CPU was set but taskset is unavailable" >&2
    exit 2
  fi
  runner=(taskset -c "$cpu" "${runner[@]}")
fi

echo "# DOP cache benchmark"
echo "# kernel=$(uname -srmo)"
echo "# cpu_pin=${cpu:-unbound}"
echo "# events=$events"
echo "# rows=$rows iterations=$iterations warmup=$warmup samples=$samples"

for layout in AOS SOA AOSOA SOAOS; do
  IFS=',' read -ra row_values <<< "$rows"
  for row_count in "${row_values[@]}"; do
    echo "## layout=$layout rows=$row_count"
    perf stat -x, -e "$events" -- \
      "${runner[@]}" \
      "--layout=$layout" \
      "--rows=$row_count" \
      "--iterations=$iterations" \
      "--warmup=$warmup" \
      "--samples=$samples"
  done
done

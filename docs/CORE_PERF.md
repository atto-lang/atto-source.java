# Core performance flight recorder

Core runtime diagnostic logging, independent of `oreslang-otel` and guest Ores
code. Disabled by default. It is not distributed tracing or application logging.

## Enable

```sh
oreslang-compiler --core-perf --platform=server program.ores
# Or with flags-2-env's canonical .cli-flags.toml:
# eval "$(flags2env export -- oreslang-compiler --core-perf)"
# ORES_CORE_PERF=true oreslang-compiler --platform=server program.ores
```

The `--no-core-perf` CLI flag overrides the environment setting. The canonical
flags-2-env schema filename is `.cli-flags.toml`, **not** `.cli-args.toml`.
The core binary recognizes the switch directly; flags-2-env need not be linked
into the JVM or invoked for the recorder to work.

The first 8192 completed timing phases use one preallocated buffer: no JSON
formatting, locks, strings, file writes, or guest object allocations occur on
instrumented requests. The disabled hot path checks one volatile reference;
the enabled path captures monotonic timestamps and atomically reserves a slot.
The fixed-capacity buffer intentionally stops collecting when full so warm
traffic cannot evict the first cold-start samples.

Backend metadata (native pthread versus lazy JVM fallback, including configured\nactor-pool sizes) is written once at dump time.\n\nEvents are serialized as `ores-core-perf.v1` JSON Lines to **stderr on clean
JVM shutdown** (Ctrl-C/SIGTERM). SIGKILL and crashes do not invoke shutdown
hooks. Each phase provides `start_ns` relative to recorder installation,
`duration_ns`, and `thread_id`; no request body, path, header, or other
user-controlled data are included. Different phases can overlap and **must not
be summed** as mutually exclusive CPU time.

The current instrumentation includes CONTROL and actor-pool startup, context
startup, HTTP listener setup, request admission/dispatch, the deadline-timer schedule and actor spawn, actor readiness and
claim, and HTTP / standard-library asynchronous I/O queue and work. HTTP
`receive` timing begins **inside** the virtual-thread handler and therefore
cannot detect how long the JDK's transport waits before scheduling that handler.
A first-request delay not accounted for by phases should be investigated with
JFR (thread start, scheduling and JIT compilation) before adding a prewarm.

## Analyze

```sh
# After stopping the instrumented server:
jq -r 'select(.schema == "ores-core-perf.v1" and .kind == "phase")
  | [.phase, (.duration_ns/1000000), (.start_ns/1000000)] | @tsv' server.log
```

`--core-perf` is a diagnostic mode; timing and buffer writes themselves can
perturb very short operations. Always compare both enabled and disabled runs.

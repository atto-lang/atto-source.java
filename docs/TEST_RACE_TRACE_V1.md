# ORES_RACE_V1 — Java/GraalVM recorder foundation

Status: **opt-in emission boundary only**. This is not whole-program instrumentation, and it must not be described as automatic data-race detection.

The `RaceTraceRecorder` runtime class serializes explicitly supplied memory events and causal task/synchronization events as JSONL. It is intended to be consumed by `oreslang-test.cli`'s `oreslang test --race`.

## Invocation boundary

`RaceTraceRecorder.fromEnvironment()` enables emission only when **both** `ORESLANG_RACE_TRACE` and `ORESLANG_RACE_RUN_ID` are provided. The path must reside in an existing directory and must not already exist. No environment variable means no recorder. Merely setting these variables does not yet instrument guest programs: the actor runtime/compiler must create and own a session and supply complete events before `--race` can be enabled as a regular language test.

The producer emits:
- `begin` with protocol version 1, backend `graal`, and run identity
- per-task `task`, `fork`, `finish`, and `join` events
- `access` with allocation-unique memory location and read/write mode
- lock/unlock and unique send/recv causal handoffs
- `end` with the exact number of event records

Trace events are serialized and bounded to 100,000 events and 10 MB. Incomplete sessions lack the terminal record and fail closed in the CLI. Values use restricted identifiers instead of arbitrary guest-controlled text.

## Integration requirements before runtime automation

1. Actor execution must register stable *logical* task identities, never infer a task from a reusable carrier thread.
2. The compiler/runtime must emit every relevant non-atomic memory access, with allocation-lifetime identities that cannot alias after recycling.
3. Only genuine ordering edges may be reported. A `join` must be emitted after child completion; `send` must represent a real published message and `recv` real delivery. Instrument atomics separately rather than reporting an unsynchronized plain write.
4. `await`, `select`, channel handoffs, cancellation and continuation re-entry must preserve per-task causal order; actor-wide serial admission can suppress races only when runtime-enforced.
5. Runtime coverage attestation, recorded schedule replay and JVM/native-image parity must be proven. Existing synthetic trace tests and the new host Java recorder test do not establish these requirements.

For a targeted smoke check, compile `RaceTraceRecorder.java` on Java 21 and emit a two-task read/write trace; the CLI should detect a conflict. Add a real Oreslang program fixture and end-to-end source-execution gate before lifting this experimental status.

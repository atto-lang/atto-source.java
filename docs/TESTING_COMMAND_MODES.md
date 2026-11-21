# Oreslang testing CLI: capability boundaries

Status: **integration draft**, October 18, 2026. These modes are wired on the
`feat/oreslang-test-mode-dispatch-20261018` branch; none is considered a
validated release feature until exact-head Java/Graal tests execute.

## Commands

| CLI command | Current implementation on this draft | What's still missing |
| --- | --- | --- |
| `oreslang test file.ores` | Execute a native Oreslang TestSuite and verify its machine summary; nonzero on failures | automatic discovery/isolation |
| `oreslang test --fuzz file.ores` | Demand actual native `fuzz_int_cases` property marker; generate seeded integer inputs in Oreslang; randomized host CriticalGate admission tests | coverage-guided mutation, arbitrary data types, dynamic corpus/seed injection |
| `oreslang test --deadlock file.ores` | Exercise bounded two-lock logical-cycle detection and detect stranded CriticalGate waits/JVM monitor deadlocks | unified Task/Future/actor/channel/RW wait-for graph |
| `oreslang test --schedule-fuzz file.ores` | Seeded CriticalGate cancellation/FIFO inputs and actual host-thread cancellation-versus-handoff race attempts | deterministic, recorded **guest** actor/Task scheduling choices |
| `oreslang test --model-check file.ores` | Exhaustively explore a 21-state two-Task/two-CriticalGate reference model; reject 2-gate cycle formation | systematic bounded exploration of arbitrary Oreslang code |
| `oreslang test --race file.ores` | Recognized, but returns **2** instead of falsely reporting safety | genuine OresVM guest read/write shadow memory and happens-before clocks (issue #1417) |
| `oreslang test --replay trace file.ores` | Verify a v2 **source-closure SHA-256** and rerun seed/count of the bounded CriticalGate-input explorer | reproduction of arbitrary guest schedule and failure trace (issue #1418) |

Modifiers `--seed=N`, `--runs=N`, and `--trace-out=path` configure the
bounded runtime explorer; `--replay=path` is also accepted. When `--fuzz`
is requested, the CLI now also validates each guest `fuzz_int_cases`
report against any explicitly specified seed and run count; a mismatch
fails closed rather than treating unrelated host stress cases as guest input
coverage. Native test summaries and fuzz counters have aggregate bounds and
reject mixed valid/malformed machine-protocol events. The line protocol
is still guest-authored stdout, not a trusted attestation. `--trace-out`
will not overwrite an existing file. Source mismatch or malformed trace
returns nonzero. **Trace format v2** supersedes entry-only v1; old v1 traces
are intentionally rejected instead of being mistaken for full-source replay.
The digest is derived from the same import-closure resolver as the guest
loader and includes all reachable raw .ores/.java unit source text, packaged
`std/*` sources, resolved importer→target mappings, project-manifest bytes,
and ordered import search roots. Project TOML is parsed from a bounded,
strict-UTF-8 byte snapshot retained in the project configuration; the digest
uses **those exact manifest bytes**, not a reopened file. For trace/record
runs, the runner hashes the **same in-memory source units it hands to the
compiler**, refusing a preflight mismatch **before compiling or executing**
guest code. The CLI also checks the closure after guest execution and before
trace creation. Changes to imported source now invalidate replay.
The digest is a snapshot, not a filesystem lock: source
modification and reversal between checkpoints remains an inherent TOCTOU
limit. It also does **not** capture the compiler binary, external FFI/JVM state,
nondeterministic OS events, or actual guest scheduler choices. Record/replay
remains `scope=critical-gate-inputs`, **not** guest scheduling replay.

Modes are combinable when their implementations overlap. For example,
`--fuzz --deadlock --schedule-fuzz` combines a real native integer-property
suite with CriticalGate runtime stress. Any combination containing `--race`
still fails closed. A combination cannot be called race-checked until that
detector executes on the same compiled guest source.

## Example: native Oreslang seeded input fuzzing

```ores
import fnc fuzz_int_cases, expect_true from '../stdlib/testing';

fnc nonnegative_square(int value): TestOutcome {
  return expect_true(value * value >= 0, "bounded square");
}

fnc test_input_fuzz(): TestOutcome {
  return fuzz_int_cases("bounded square", 17, 97, nonnegative_square);
}
```

See the complete runnable fixture `examples/native-fuzz-testing.ores`.
`fuzz_int_cases` generates a bounded, deterministic integer sequence with a
caller-supplied 0..65520 seed, and emits `ORES_TEST|FUZZ|seed=...|cases=...`.
The CLI rejects `--fuzz` when no native property generator actually ran.
`--seed=N` validates the guest generator's printed seed; the host does not
secretly override a seed in guest code. The PRNG is for test input generation,
not cryptography; there is no coverage-guided corpus management yet.

## Runtime model and race semantics

The finite model enumerates ownership and pending waits of two continuations
over two exclusion gates, admitting FIFO handoff, cancellation, releasing and
cycle rejection. It reaches 21 unique states and includes double-lock deadlock
attempts. This verifies the *reference model*, not arbitrary guest bytecode.
The stress engine invokes real `CriticalGate` methods, repeatedly cancelling
waiters and releasing permits under seeded input order. It also races a
virtual-thread canceller against actual permit handoff. Operating-system
thread interleavings are not deterministic, even with the same seed.

A race detector cannot be simulated by rerunning a program repeatedly or by
checking for JVM monitor deadlocks. A real `--race` requires logical Task IDs,
generation-tagged memory places, atomic semantics, known synchronization
edges, instrumented FFI boundaries, and coverage accounting. Reject
uninstrumented shared memory rather than returning clean.

An Oreslang `critical` logical ticket may survive `await` while platform
carriers remain available; an unvalidated mutable state loan must not. Public
Future cancellation is **not** physical guest retirement. See stacked PR
#1416 for the retirement barrier and #1410 for nonparking carrier guards.

## Verification gates

1. Exact-head GraalVM 25 execution of `NativeTestCommandModesTest`,
   `NativeTestingFrameworkTest`, `OresTestExplorationTest`, and existing
   critical physical-retirement/cancellation tests.
2. Independently authored source fixtures with known race/deadlock bugs and
   demonstrated detector positives; test clean negatives and instrumentation
   coverage reports, not just absence of exceptions.
3. Native Image AOT/hybrid and LLVM guest-source parity.
4. True OresVM deterministic scheduling + trace minimization, and
   vector-clock race detection, before promoting their feature flags.

Zero-step GitHub Actions failures provide **no executed verification**.

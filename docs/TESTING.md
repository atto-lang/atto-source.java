# Native Oreslang testing

Oreslang's user-facing test framework is implemented in Oreslang itself at
`stdlib/testing.ores`. It is not a Java/JUnit wrapper.

The Java/Truffle repository still uses host-side tests to verify the compiler and
runtime implementation, but application/library tests can be ordinary `.ores`
programs using the native framework.

## Design goals

The first native runner takes the parts of modern Go, Rust, Node.js, and Scala
test ecosystems that are useful at language-core level without pretending that
unfinished runtime services already exist.

Current surface:

- immutable test definitions instead of a process-global mutable registry;
- deterministic source order;
- hierarchical slash-separated names such as `math/mean/empty`;
- one exact hierarchical-name filter through `TestOptions.only` (`""` runs all);
- explicit skips with reasons;
- fail-fast as an option, not a semantic default;
- stable human-readable output;
- stable line-oriented machine output (`ORES_TEST|...`) for CI/tooling;
- reusable assertion helpers;
- deterministic table/property checks with caller-owned cases;
- aggregate `RunSummary`;
- non-zero process failure through `require_success`.

The machine protocol is deliberately simple and line-oriented. Native JSON
reporting should be added when the standard string/JSON layer can guarantee
correct escaping rather than emitting almost-JSON.

## Why this shape

Modern Go testing emphasizes hierarchical subtests, filtering, explicit
parallel opt-in, fuzzing, and machine-readable output. Rust's built-in/Cargo
test flow emphasizes deterministic discovery, name filtering, ignored tests,
captured output, and separate human/machine diagnostics. Node's built-in test
runner emphasizes subtests, explicit concurrency/isolation, reporters, mocks,
snapshots, and coverage.

Oreslang adopts the portable semantic pieces now and leaves scheduler/filesystem
features gated on the native primitives that must enforce them correctly.

## Example

```ores
import class TestCase, TestSuite, TestOptions, RunSummary, TestOutcome from '../stdlib/testing';
import fnc expect_equal_int, run_suite, require_success from '../stdlib/testing';

fnc adds(): TestOutcome {
  return expect_equal_int(2 + 2, 4, "addition");
}

pub routine main(): void {
  val TestSuite suite = new TestSuite(
    "math",
    arr[new TestCase("adds", adds, false, "")]
  );
  val TestOptions options = new TestOptions("", false, false);
  val RunSummary summary = run_suite(&suite, &options);
  require_success(&summary);
  return;
}
```


## Experimental concurrency-test command (Java/Graal host)

The Java/Graal launcher now recognizes an explicit `test` subcommand. This
runs a real `stdlib/testing.ores` entrypoint; a process that prints no native
`ORES_TEST|SUMMARY|` is **not** considered a passing test suite.

```sh
oreslang test examples/native-testing.ores --deadlock
oreslang test examples/native-testing.ores --schedule-fuzz --seed=42 --trace-output=run.trace
oreslang test examples/native-testing.ores --replay=run.trace
oreslang test examples/native-testing.ores --fuzz --iterations=16 --seed=42
```

This is a bounded, **experimental** host-side slice, not a claim of a fully
deterministic guest scheduler:

- `--deadlock` reports cycles already detected by the scheduler's pending
  Future-await graph and SharedMutex's wait-for graph, including cycles caught
  by guest code. It does **not** prove global deadlock freedom. It does not yet
  cover remote actors, arbitrary channels, or every form of starvation.
- `--schedule-fuzz` introduces seeded `yield`/short park decisions at Oreslang
  scheduler task-turn boundaries. Actual OS carrier scheduling can still
  differ between runs.
- `--fuzz` performs seeded repeated schedule-perturbation runs and passes
  `ORES_TEST_SEED` to the guest program environment for tests that explicitly
  use it. The host does not yet generate or shrink arbitrary guest input data.
- `--replay` verifies the sequence of checkpoint kinds and replays recorded
  *choices*. It fails closed on extra/missing/mismatched checkpoints, but does
  not promise bit-for-bit physical thread scheduling.
- `--trace-output=path` writes `ORES_CONCURRENCY_TRACE_V1` (bounded to 100,000
  events per run); failures save a trace under `.oreslang/failures` by default.
- `--race` and `--model-check` are reserved and intentionally return exit 2
  until guest memory-access/happens-before instrumentation and bounded guest
  transition modeling exist. No successful race-free or model-checked verdict
  can be issued from this host-only scheduler trace.

Implementation paths: `ConcurrencyTestCommand`, `ConcurrencyTestProbe`,
`OresScheduler`, and `OresMutex`. The probe is inactive in ordinary program
execution. The CLI is Java/Graal-only at this stage; native LLVM parity and
freestanding-core support are still pending. Treat the safety flags as test
evidence only for the instrumented surfaces, never as a formal proof.

## Follow-ups

The following should not be simulated in userland before their native
dependencies are available:

1. scheduler-backed parallel test execution and per-test isolation;
2. monotonic-clock timeouts and virtual-time testing;
3. filesystem-backed snapshots;
4. coverage collection;
5. corpus fuzzing with shrinking/minimization;
6. regex/glob filtering once the standard string/regex library is stable;
7. automatic project test-file discovery in the Rust Oreslang CLI.

Those additions should preserve the existing `TestCase` / `RunSummary`
semantics and machine event contract.

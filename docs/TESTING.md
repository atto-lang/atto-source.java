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
  val RunSummary summary = run_suite(rt borrow suite, rt borrow options);
  require_success(rt borrow summary);
  return;
}
```


## Experimental concurrency-test command (Java/Graal host)

The Java/Graal launcher now recognizes an explicit `test` subcommand. This
runs a real `stdlib/testing.ores` entrypoint; a process that prints no native
`ORES_TEST|SUMMARY|`, reports zero passed cases (including all-skipped
suites), or disagrees with PASS/FAIL/SKIP case counts is **not** considered
a passing test suite. `--trace-output` is limited to one iteration and cannot
overwrite the test entrypoint or a replay input.

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
  Separately, native `stdlib/testing.ores` now provides
  `check_fuzz_int_cases(name, seed, samples, property)`: bounded deterministic
  integer generation (up to 4096 samples), with simple integer-only shrinking
  candidates. See `examples/native-fuzz-testing.ores`. The guest property must
  be deterministic/pure for replay and shrinking to be meaningful.
- `--replay` verifies the recorded checkpoint kinds and choices, and
  refuses mismatched seed, test-entry SHA-256, workspace source fingerprint,
  backend mode/platform or probe version. An omitted `--seed` inherits the
  recorded seed. It is **not** deterministic physical thread scheduling.
- `--trace-output=path` writes `ORES_CONCURRENCY_TRACE_V3` for CLI runs
  (seed, entry-file SHA-256, bounded workspace source digest, backend identity,
  probe version), with at most 100,000 checkpoint events. The fingerprint
  conservatively includes `.ores`, compiler-language sources and schema manifests
  under the current workspace and excludes generated output. This is **not**
  a compiler-resolved transitive import closure or a trusted runtime binary
  digest; imports outside the workspace and actual compiler builds must be
  pinned through separate exact-tree CI evidence. Older V1/V2 traces may be
  examined diagnostically but are rejected for CLI replay.
- `--trace-output` is single-iteration only; the CLI rejects traced symlinks
  and source/replay aliases. Trace creation uses `CREATE_NEW`: an existing trace
  file or symlink is never overwritten; choose a fresh output path per run.
  Failures save traces under `.oreslang/failures`, and identical reruns may
  fail rather than clobber prior evidence. Neither path checks nor a workspace
  fingerprint are an authentication mechanism for concurrently mutable inputs.
- A bounded host-only `ConcurrencyRaceAnalyzer` already supports vector-clock
  read/write conflict checking and happens-before edges for **owner-validated**
  reentrant lock release/acquire (synchronization published only on outermost
  release) and globally single-use actor-message IDs. Every participant ID
  must denote an independently executing logical task/message lifetime,
  including distinct lanes within a concurrently executing actor; using only
  the actor reference as an identity would hide races. Labels containing
  control characters are rejected. Host-only `fork(parent, child)`,
  `finish(child)`, and `join(parent, child)` now model typed task-lifecycle
  ordering: a fresh child inherits only pre-fork parent time, and exactly one
  direct-parent join may import a finished child's clock. Unjoined children and
  siblings remain unordered. This API is **not** wired to full guest actor
  lifecycle events yet, so it must not certify guest memory-race freedom.
  It requires a
  **complete trusted stream of memory-access events**; no guest instrumentation
  feeds it yet, so it cannot certify a program as race-free.
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

### Exact-tree concurrency P0 gate

On an authorized private runner, check out the exact PR tree (never a partial
cherry-pick) and set `EXPECTED_SOURCE_TREE_SHA` to its verified Git tree SHA:

```sh
EXPECTED_SOURCE_TREE_SHA=<40-character-tree-sha> bash scripts/ci-verify-concurrency-p0.sh
```

The gate must execute the tests and print `ORES_CONCURRENCY_CI|...|result=PASS`
only after success. Matching Git tree SHA and executable CI logs are required;
failed pre-step GitHub Actions jobs do not count as test evidence. This
script does not grant access to private source across organizations.

To run the entire repository test suite alongside the targeted exact-tree gate,
set `RUN_FULL_SUITE=1` when invoking `scripts/ci-verify-concurrency-p0.sh`.
This requires a working authorized runner; scripts and setup alone do not
constitute executed test evidence.

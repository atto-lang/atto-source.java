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

## Additional native regression suites (draft, guest execution unverified)

The proposed v2 branch adds three new source-owned test groups:

| Guest file | Authored cases | Purpose |
| --- | ---: | --- |
| `examples/native-testing-regressions-40.ores` | 40 PASS cases | Predicate and typed equality/inequality behavior, positive and rejected outcomes, exact failure detail, floating tolerance, nonempty/empty properties and result fields. |
| `examples/native-testing-runner-matrix.ores` | 12 accounting scenarios | Empty/skip/filter cases, mixed pass/fail, exact hierarchical filtering, fail-fast counts and nested names. Runs nested suites in human mode so they do not forge machine receipts. |
| `examples/native-testing-negative-{empty,skipped,filtered,failed}.ores` | 4 deliberately failing programs | `require_success` must reject vacuous, all-skipped/all-filtered and failed suites. The JVM CI gate requires **both** an exact guest-emitted summary and a nonzero process exit, preventing an ordinary compiler failure from satisfying the negative case. |

The positive 40-case guest must produce exactly
`ORES_TEST|SUMMARY|total=40|passed=40|failed=0|skipped=0|filtered=0`
with 40 unique `PASS` receipts and one summary; runner matrix must print
`ORES_TEST_RUNNER_MATRIX_OK|cases=12` only after all its checks pass.
JVM, native AOT and native hybrid workflows invoke the positive suites;
JVM additionally verifies the four intentional failures. The 40-case machine
receipts are checked against the exact, ordered
`tests/native-testing-regressions-40.expected-ids.txt` file, rather than
only checking summary counts. Fast CI invokes the
positive suites too. The other backends remain unverified.

**Counts describe authored fixtures, not executed/green tests.** GitHub
Actions jobs with zero recorded steps establish only runner-admission
failure, and host/static source checks never substitute for a real compiler
executing the Oreslang guest. Keep draft until actual exact-head jobs run.


### Trusted ordered case-ID admission (source-free verifier contract)

Six existing native guest suites now require checked-in ordered case-ID lists:
`stdlib` (40), `native-testing-core` (30),
`compiler-scalar-parity-batch4` (24), `harness-meta-batch4` (12),
`harness-accounting-batch5` (16), and `portable-scalar-v2` (10).
That covers **132 previously authored TestCase registrations** in this repo;
it is not 132 additional tests.

CI must provide the four-argument `verify_ores_test_receipts.sh` invocation
for all 132 cases so that substituting a different valid-looking PASS ID,
changing the PASS order, or manufacturing a case cannot satisfy the gate
merely by preserving the summary totals. The new
`tests/test_native_test_manifests.sh` source-free check compares the ordered
native `.ores` declarations to their trusted manifests, checks synthetic
positive receipts, and rejects an injected forged ID for each suite.
`tests/test_ores_test_expected_ids.sh` also runs adversarial 40-case
synthetic mutations. Neither source-free test proves compiler admission,
actual guest execution, runner identity, or receipt authenticity.

Continue using real compiler-backed exact-head job receipts as the only
evidence of actual Oreslang test execution, with independent review and
security-controlled runner admission before merge.

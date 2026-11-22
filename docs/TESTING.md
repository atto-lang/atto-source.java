# Native AttoLang testing

AttoLang's public testing framework is implemented in native AttoLang at
`stdlib/testing.atto`. The older `stdlib/testing.ores` is an explicit
migration-compatibility resource, not the canonical new source. The test
framework is not implemented as a Java/JUnit wrapper.

The Java/Truffle repository still uses host-side tests to verify the compiler and
runtime implementation, but application/library tests can be ordinary `.atto`
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

AttoLang adopts the portable semantic pieces now and leaves scheduler/filesystem
features gated on the native primitives that must enforce them correctly.

## Example

```atto
import class TestCase, TestSuite, TestOptions, RunSummary, TestOutcome from 'ores.std/testing';
import fnc expect_equal_int, run_suite, require_success from 'ores.std/testing';

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
| `examples/native-testing-regressions-40.atto` | 40 PASS cases | Predicate and typed equality/inequality behavior, positive and rejected outcomes, exact failure detail, floating tolerance, nonempty/empty properties and result fields. |
| `examples/native-testing-runner-matrix.atto` | 12 accounting scenarios | Empty/skip/filter cases, mixed pass/fail, exact hierarchical filtering, fail-fast counts and nested names. Runs nested suites in human mode so they do not forge machine receipts. |
| `examples/native-testing-negative-{empty,skipped,filtered,failed}.atto` | 4 deliberately failing programs | `require_success` must reject vacuous, all-skipped/all-filtered and failed suites. The JVM CI gate requires **both** an exact guest-emitted summary and a nonzero process exit, preventing an ordinary compiler failure from satisfying the negative case. |

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
executing the AttoLang guest. Keep draft until actual exact-head jobs run.


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
native `.atto` declarations to their trusted manifests, checks synthetic
positive receipts, and rejects an injected forged ID for each suite.
`tests/test_ores_test_expected_ids.sh` also runs adversarial 40-case
synthetic mutations. Neither source-free test proves compiler admission,
actual guest execution, runner identity, or receipt authenticity.

Continue using real compiler-backed exact-head job receipts as the only
evidence of actual Oreslang test execution, with independent review and
security-controlled runner admission before merge.


## Canonical AttoLang source migration and backend dependency

New native test sources are **`*.atto`**, including
`stdlib/testing.atto`. Java CI invokes `.atto` guest entries for the
standard-library regressions, scalar/property suites, test runner matrix, and
the four expected-failure guests. The compiler-owned library registry maps
extensionless `ores.std/testing` and explicit `ores.std/testing.atto` to the
packaged `testing.atto` resource; only an **explicit**
`ores.std/testing.ores` import selects the legacy resource. The
`ores.std` import namespace, `dev.oreslang` Java package, and
`ORES_TEST|...` v1 wire protocol remain **compatibility identifiers** and
must not be silently renamed until versioned compiler/CLI parity exists.

The source-extension admission work is a **cross-PR dependency**:
Java/GraalVM compiler input, imports, and source-graph handling must be
integrated with the canonical `.atto` work tracked in PR #2460
(and the related #2459/#2486 migration) before this harness can be
accepted on `main`. The test harness PR intentionally **fails closed**
if the compiler cannot execute `.atto`, rather than quietly running an
old `.ores` copy. Existing `.ores` files and the reviewed five-backend
scalar fixture manifest are kept only as explicit transitional evidence;
`.atto` scalar guest files must match their pinned legacy blob identities.

The `tests/test_atto_harness_migration.sh` source-free gate checks the
canonical source files, package registry, Maven resource configuration, and
JIT/AOT/hybrid CI paths, including downgrade mutations. The
`AttoTestingStdlibSourceTest` Java/JUnit integration test checks the
packaged `testing.atto` source resolution, explicit legacy fallback,
and reject-by-default import paths. **These checks do not prove guest
execution.** Admitted, independently reviewed exact-head compiler-backed
AttoLang steps are still mandatory.


## Native Atto testing hardening — range and summary accounting

The canonical stdlib/testing.atto provides expect_in_range_int(actual, lower,
upper, message). Bounds are **inclusive**; reversed bounds and values outside
the range return a failing TestOutcome with the caller's diagnostic. The
implementation compares endpoints directly and never constructs upper + 1
or lower - 1, so it is safe at both signed-i64 limits.

summary_is_valid(borrow RunSummary) checks that all counters are nonnegative
and that total == passed + failed + skipped without adding untrusted
integers. filtered is nonnegative and deliberately excluded from total:
it counts cases that never entered the selected suite. Because fail-fast only
counts attempted cases, early-exit summaries also meet this invariant.
require_success now rejects inconsistent summaries *before* computing an
executed count; it continues to reject all-empty/all-skipped/all-filtered and
failed suites.

The positive examples/native-testing-summary-range.atto fixture exercises
range endpoints and corrupted summaries. The negative
examples/native-testing-negative-forged-summary.atto fixture must compile,
reach require_success, and fail at runtime. Both are wired into
AttoNativeTestingGuestIntegrationTest without changing the pinned 40-case
machine receipt inventory or expanding the fixed JUnit method count.

**Evidence status:** source and test wiring are authored; an admitted,
exact-head GitHub Actions run with real guest/compiler steps is still required
before classifying these guest tests as PASS. This change is Java compiler-local
and does not assert LLVM, Rust/Wasm, JavaScript, Forge, or .NET parity.
Seeded property generation/shrinking remains a distinct overlapping Java
proposal in atto-source.java PR #1747; this patch does not duplicate it.

## Cross-repository `atto test` integration (candidate, not yet executed)

The Java/GraalVM compiler branch contains a source-owned `atto-test.toml`
for the Rust `atto-test.cli` candidate PR #11. It selects exactly the
canonical `examples/native-testing-regressions-40.atto` guest and launches
the existing `dev.oreslang.launcher.OresMain` Maven entry point using an
argv template, without shell interpolation:

```sh
# Requires JDK 25, Maven, and the independently built atto-test.cli binary.
# Put atto-test.cli/bin on PATH, then from this Java compiler checkout:
atto test --root . --allow-project-command
```

The `--allow-project-command` opt-in is required because a project
manifest can otherwise select arbitrary host commands. Use only on a
reviewed, trusted checkout without ambient secrets; the CLI is not a
sandbox. `ATTO_BIN` remains available for compiler backends that take a
single source path directly. This Java integration instead selects Maven
through the explicit `[native].command`.

A successful local CLI output is **not by itself** independently
authenticated guest test evidence. CI must bind the exact Java source tree,
CLI version, compiler/runtime, trusted runner, the 40 expected test IDs,
nonzero executed steps, process exit, and guest-generated output.
The existing Java source-owned harness remains the independent receipt
gate. Backend parity, AOT/hybrid status, and Rust CLI source-head execution
are not verified by adding this manifest.

# Core and stdlib formal verification — pilot

This pilot uses **Z3 over SMT-LIB2** to check three *reviewed, abstract*
Oreslang stdlib models, and a Rust fail-closed gate for the solver's complete
output. It is intentionally separate from the runtime's bounded actor models.

## Proof coverage and scope

| File | Logic / scope | Obligations | Negative controls |
| --- | --- | --- | --- |
| `core-int64.smt2` | QF_BV signed 64-bit, arbitrary inputs | 16 UNSAT counterexample queries: min/max bounds, selection and commutativity; clamp reversed-bound policy, in-range selection, idempotence and monotonicity | 2 SAT queries: swapped min, missing upper clamp |
| `trace-buffer-counters.smt2` | QF_BV signed 64-bit, single-owner counter transition | 9 UNSAT queries: valid-capacity empty constructor base, occupancy conservation, invalid-input no-op, full rejection, at-most-once admission, saturating dropped count, nondecrease | 3 SAT queries: incorrect full admission, missing drop saturation, zero-capacity constructor |
| `testing-summary.smt2` | QF_BV signed 64-bit, one-step suite accounting for up to 1,024 processed cases | 9 UNSAT queries: empty-suite base, total/filtered partition, passed/failed/skipped conservation, fail-fast state, single case advancement, and bounded progress | 3 SAT queries: uncounted failure, doubly counted skip, malformed empty-suite counters |

**UNSAT** means that no counterexample exists in the entire fixed-width bit-vector
domain under the model's definitions and stated preconditions; this is not
randomized testing or merely bounded enumeration of test inputs. For
`Trace.Buffer` and `testing.run_suite`, these are base-case and *inductive
one-transition invariant* proofs **inside the abstract models**. A real guest
constructor or runner entrypoint must independently establish the modeled
initial state before any end-to-end source guarantee can be claimed.

The models intentionally follow the *current* implementations:
- `core.clamp_int(value, lower, upper)` returns **lower** when `lower > upper`.
- `Trace.Buffer.record` rejects malformed events without increasing drops;
  valid events rejected at capacity increment `dropped` until 2147483647,
  after which the counter saturates. Accepted events increment occupancy once.
- `testing.run_suite` maintains `total = passed + failed + skipped` for
  attempted selected cases; `filtered` counts excluded cases, and fail-fast
  stops iterating immediately after the first selected failure. The model uses
  an explicit 1,024-step bound, not a proof of arbitrary unbounded loops.

## Guardrail: no silent source drift

`verify.rs` compares the Git blob identities of `stdlib/core.ores`,
`stdlib/trace.ores`, and `stdlib/testing.ores` to independently reviewed ids. Any source modification,
including a documentation-only modification, fails CI until a reviewer checks
the implementation, updates the abstract model if necessary, and deliberately
updates the source-identity constants. The gate then demands **exactly**
16/9/9 `unsat` results followed by 2/3/3 `sat` negative controls. Missing solver,
nonzero process exit, wrong output, `unknown`, an extra result, or changed
source all fail closed.

The source-identity check is an *integrity guard*, **not an automatic semantic
translation or refinement proof**. A reviewed SMT encoding might be wrong or
incomplete; source changes that modify the implementation require independent
review. Git blob SHA-1 is used as repository identity, not as a tamper-proof
security attestation against chosen-prefix collision attacks.

## Running locally

From the repository root with `git`, `rustc`, and `z3` on PATH:

```sh
rustc --edition=2021 --test formal/stdlib/verify.rs -o /tmp/ores-stdlib-formal-tests
/tmp/ores-stdlib-formal-tests
rustc --edition=2021 formal/stdlib/verify.rs -o /tmp/ores-stdlib-formal
/tmp/ores-stdlib-formal
```

GitHub Actions `stdlib-smt.yml` installs the solver/runtime, reports the
toolchain versions and checks the exact branch tree. This first pilot does not
pin a hermetic solver binary: **release-grade reproducibility must pin Z3's
verified binary/digest and archive the proof transcript**.

## What this does NOT certify

- The Oreslang compiler's `int` is exactly signed 64-bit on every backend;
  parser/type admission, unsigned conversions and integer ABI need separate
  executable checks.
- Java/Graal execution of the packaged `core` module refines the SMT model,
  especially `INT64_MIN`, `INT64_MAX` and reversed clamp bounds.
- Actor ownership, Java off-heap storage, runtime/FFI memory barriers or
  zero-GC behavior.
- Physical FIFO behavior of `Trace.Buffer.items`, event payloads, `at()`
  indexing, constructor failure, concurrent calls or capacity reclamation.
- LLVM/.NET/Wasm/JS parity; there is no cross-backend proof here.
- `testing.run_suite` callback scheduling, exact emitted output, early return
  guest semantics, overflow for suites beyond the bound, or the ability to
  construct the modeled `TestCase` list on every target.
- Liveness/progress/fairness or arbitrary-width integers.

**Promotion gates**: use the existing exact-head JVM/AOT guest invocation with
an edge-value table, and add LLVM/.NET/Wasm/JS executable refinement fixtures as
those backends implement the corresponding APIs. Record exact source/commit SHA,
binary provenance, observed output and failing negative guest cases. Keep formal
model success and physical backend verification as separate CI statuses.

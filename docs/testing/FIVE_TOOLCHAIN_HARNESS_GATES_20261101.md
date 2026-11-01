# Five-toolchain Oreslang test harness contract (2026-11-01)

## Status: one common source language, two distinct execution tiers

The **canonical native hosted test harness** is `ores.std/testing` from
`oreslang-source.java/stdlib/testing.ores`. It provides `TestCase`,
`TestSuite`, `TestOptions`, `RunSummary`, assertions, deterministic
ordered execution, and `ORES_TEST|` machine events. The implementation
is written in Oreslang, not Java/C#/Rust/Python. Full `TestSuite` execution
is NOT verified on all five requested toolchains.

The **portable scalar assertion subset** is separately defined as one native
Oreslang program per test:

```ores
pub fnc main():int{return (2 + 3 * 4) - 14;}
```

Return **0** indicates success; other signed returns must not be counted as
passing. This is necessary for current single-function scalar bootstraps.
The host drivers are permitted to launch actual compilers and observe the
exit code (or Wasm i64 return), but cannot synthesize the result.

## Exact-source fixture contract

All five source compiler repositories carry identical bytes under
`contracts/parity/native-scalar-zero-v1/`. The shared `cases.tsv` Git
blob is **`a87f64af3e448ba10927eb2932900679896b283b`**. All six
source file blobs were checked against each repository's branch:

| File | Expected source Git blob | Outcome |
| --- | --- | --- |
| `precedence-zero.ores` | `04a7143024cd2e840a91364e507d6175b23562cd` | 0 |
| `i64-precision-zero.ores` | `9a079a4c05df524efbb3dbcf3a93186a3b247284` | 0 |
| `signed-opposite-zero.ores` | `db3fa0aa9a014d043f485dbfb15d80e5267dc673` | 0 |
| `nested-subtraction-zero.ores` | `113cf599997e66c11686e8efe623d325bd052e9d` | 0 |
| `min-boundary-zero.ores` | `8ab09ede2327725b516848f88c3663bdcd85689d` | 0 |
| `reject-removed-operator.ores` | `756d145c5850f7a26f6f82a6ff1cda27416086e3` | parser rejection |

These six **source fixtures** in each of five repos total 30 new native
`.ores` files. They are six logical tests, not 30 distinct semantic cases.
They do NOT increase the 132 previously recorded native
`TestCase` registrations; a source fixture without `TestCase` is counted
in a separate corpus lane.

## Backend execution wiring and limitations

| Backend | Actual source-to-result gate | Full `ores.std/testing` status |
| --- | --- | --- |
| Java/Graal | `scripts/native_scalar_zero_java_smoke.sh` invoked by fast-ci; five Java-compiled zero guests, nonzero mutant, removed-% rejection | Native `.ores` framework exists; exact-head JIT/AOT/hybrid receipts still required |
| LLVM | `tests/native_scalar_zero_smoke.sh` invoked by `.github/workflows/ci.yml`; native LLVM compiler, Clang-linked guest exit zero, removed-% negative | Native full suite import/compile/receipt gate not yet demonstrated |
| .NET | `NativeScalarZeroTests.Run()` in `verification/Ores.Net.Tests` reads pinned `.ores` fixtures, checks typed IR and emitted CIL results; includes rejected-% and nonzero mutant | CIL evaluator is NOT published NativeAOT guest; `ores.std/testing` import/class/callback support not yet demonstrated |
| WASM | `tests/native-scalar-zero-parity.test.mjs` in `npm test`; with pinned native LLVM compiler, compiles sources to isolated, import-free Wasm and compares returned i64 0n; rejected-% negative | Only the import-free `main:()->i64` ABI exists. No hosted `ores.std/testing` imports, classes or stdout receipts |
| Cranelift | `tests/portable_scalar_zero.rs` picked up by Cargo integration tests; invokes real Cranelift CLI for five zero guests, rejected-% and nonzero-mutant cases | Bootstrap frontend admits one scalar `main` function only; does NOT parse classes, imports or the hosted harness |

### Required CI and proof rules

1. **Snapshot identity:** exact PR head commit, tree SHA, compiler binary
   SHA-256, source fixture Git blobs, test driver version, CI job attempt.
2. **Runner admission:** nonzero executed workflow steps; `steps=[]`
   means NO executed test result. Do not infer any pass from workflow name.
3. **Actual guest:** source passed to that backend's real compiler and
   executed as a guest. .NET CIL interpreter/IR checks are separately labeled
   until a real NativeAOT binary is run. Wasm must require
   `ORESLANG_SCALAR_PARITY_REQUIRED=1` and pinned LLVM compiler identity.
4. **Negative mutations:** at least one valid nonzero assertion must be
   observed as failure and a removed-language operator must be rejected
   *before* a guest artifact is published. Host arithmetic alone is not guest
   evidence.
5. **Full harness parity:** the **same** reviewed native `stdlib/testing.ores`
   implementation and test definition source must compile and execute on
   Java, LLVM, .NET, WASM and Cranelift. It must produce exact-case-ID,
   ordered `ORES_TEST` receipts and matching SUMMARY, reject all-skipped or
   all-filtered suites, propagate failing exit status, and prove skip/filter,
   fail-fast, property, escape and 64-bit cases. If a backend lacks imports,
   closure/callbacks, lists, strings, Results, stdout or structured errors,
   mark **BLOCKED**, not PASS.
6. **Ownership/operations:** `borrow T` / `rt borrow` only; no `&`/`*`
   pointer syntax, no implicit conversions or removed `% ^ ^^` operators.
   Do not execute native code inside a blocked actor carrier.
7. **Governance:** do not merge overlapping Java #2393 and #2395
   independently. Retain independent review and required branch protection.
   Private source transfer across organizations requires explicit
   authorization; unused Actions budget is not authorization.

## Remaining implementation dependencies

**LLVM:** prove packaged `ores.std/testing` import resolution, class and
closure ABI, list iteration, strict stdout and `require_success` behavior.
**.NET:** wire the same import/module runtime graph plus TestCase closure
invocation into emitted/native AOT code (not only CIL reference evaluator).
**WASM:** add an explicitly capability-gated hosted std profile distinct
from the current import-free `ores.core` scalar profile; never grant OS I/O
implicitly. **Cranelift:** move from one-expression parser to verified
multi-declaration symbol graph, typed callbacks/collections and owned
stdlib imports, or delegate to a reviewed shared compiler frontend.
**All:** emit the same canonical receipts, negative mutation verdicts and
exact-head verified guest evidence.

Until all of these are met, the **portable scalar kernel is a useful
five-way test subset; it is not the full cross-backend stdlib harness.**

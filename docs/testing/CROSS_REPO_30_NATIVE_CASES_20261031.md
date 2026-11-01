# Native Oreslang test harness rollout — 2026-10-31
Test cases: **52 authored `TestCase` registrations in 20 repositories** (initial batch 30, second batch 20, plus two canonical stdlib meta-cases), one `tests/test_std_harness_regressions.ores` per repository, authored in Oreslang alone.

| Repository | Draft PR | Added cases |
| --- | --- | ---: |
| `oreslang-http-routing` | [#31](https://github.com/ores-truffle-oreslang/oreslang-http-routing/pull/31) | 3 |
| `oreslang-linked-queue` | [#15](https://github.com/ores-truffle-oreslang/oreslang-linked-queue/pull/15) | 3 |
| `oreslang-otel` | [#15](https://github.com/ores-truffle-oreslang/oreslang-otel/pull/15) | 3 |
| `oreslang-rpc` | [#17](https://github.com/ores-truffle-oreslang/oreslang-rpc/pull/17) | 3 |
| `oreslang-ssr` | [#25](https://github.com/ores-truffle-oreslang/oreslang-ssr/pull/25) | 3 |
| `postgres-orm.ores` | [#14](https://github.com/ores-truffle-oreslang/postgres-orm.ores/pull/14) | 3 |
| `oreslang-web-browser.ores` | [#14](https://github.com/ores-truffle-oreslang/oreslang-web-browser.ores/pull/14) | 3 |
| `oreslang-sg-stategraph.ores` | [#7](https://github.com/ores-truffle-oreslang/oreslang-sg-stategraph.ores/pull/7) | 3 |
| `oreslang-data-science` | [#21](https://github.com/ores-truffle-oreslang/oreslang-data-science/pull/21) | 3 |
| `oreslang-logic.ores` | [#5](https://github.com/ores-truffle-oreslang/oreslang-logic.ores/pull/5) | 3 |
| `oreslang-hpc-finance` | [#19](https://github.com/ores-truffle-oreslang/oreslang-hpc-finance/pull/19) | 2 |
| `oreslang-serialization-and-validation` | [#48](https://github.com/ores-truffle-oreslang/oreslang-serialization-and-validation/pull/48) | 2 |
| `oreslang-orie-ieor` | [#4](https://github.com/ores-truffle-oreslang/oreslang-orie-ieor/pull/4) | 2 |
| `oreslang-forward-proxy-demo.ores` | [#32](https://github.com/ores-truffle-oreslang/oreslang-forward-proxy-demo.ores/pull/32) | 2 |
| `oreslang-reverse-proxy-demo.ores` | [#34](https://github.com/ores-truffle-oreslang/oreslang-reverse-proxy-demo.ores/pull/34) | 2 |
| `oreslang-platform-conformance` | [#269](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/269) | 2 |
| `oreslang-source.java` | [#2395](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2395) | 4 |
| `oreslang-source.llvm` | [#1252](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1252) | 2 |
| `oreslang-source.net` | [#279](https://github.com/ores-truffle-oreslang/oreslang-source.net/pull/279) | 2 |
| `oreslang-compilers-parity-testing` | [#15](https://github.com/ores-truffle-oreslang/oreslang-compilers-parity-testing/pull/15) | 2 |

## Contract
- The 19 consumer suites now import the canonical hosted `ores.std/testing` module (the Java compiler repository's own tests import `../stdlib/testing` for local branch testing). `CoreLibrarySources.java` includes `testing.ores` in its explicit `STD_SOURCES` registry and recognizes `ores.std/`; `pom.xml` packages `stdlib/**/*.ores` to `dev/oreslang/std`. This is source-level evidence of the intended Java/Graal packaging path, **not** compiler-backed import execution or cross-backend parity.
- Each suite uses deterministic, ordered `TestCase`s, `TestOptions("", false, true)`, strict `ORES_TEST` machine receipts and `require_success(rt borrow summary)`.
- Pointer-free `rt borrow` call sites are used. Canonical harness definitions and example were adjusted to `borrow T` parameters and `rt borrow` calls on this branch.
- These are **authored test cases, not passes**. File read-backs confirm initial 20 files/30 registrations and the subsequent 22 authored cases, but no compiler-backed guest results have been credited.
- The native testing harness now escapes newline, CR, tab, NUL, backslash and pipe in per-case machine fields and emits each case status using one stdout call. This does not authenticate receipt provenance or prove concurrent cross-process line atomicity.
- At least five sampled exact-head GitHub Actions failures were `steps=[]`, so are runner admission failures, not failing test bodies.
- Compiler-backed evidence requires on-source-org exact-head identity, admitted stepful jobs, installed compiler/version identity, native guest execution of each new test, unique PASS IDs, and matching SUMMARY totals.
- Independent review and no private-source cross-organization transfer without specific authorization remain hard gates.

## Acceptance order
1. Compile each suite using the compiler-owned `ores.std/testing` module at pinned compiler and consumer repository SHAs; confirm installed `ores.std` resources match the reviewed source.
2. Admit a source-org runner and confirm at least one executed step per job; otherwise do **not** count CI as a test result.
3. Execute native Oreslang guest cases and validate machine receipts, failure exit behavior, and expected counts.
4. Repeat on relevant backend profiles (Graal/JVM, LLVM, .NET, WASM where supported); report unsupported semantics separately.
5. Obtain independent review before moving any draft to ready or merging. Do not use host-only static gates as compiler/guest evidence.

## Incremental cases (second pass)
- `oreslang-http-routing`: zero match-step budget.
- `oreslang-linked-queue`: invalid removal preserves live-chain invariants.
- `oreslang-otel`: JSON newline escaping.
- `oreslang-rpc`: rejects unnamed named-message references.
- `oreslang-ssr`: blocks untrusted event-handler attributes.
- `postgres-orm.ores`: retains workspace/ID as positional SQL binds.
- `oreslang-web-browser.ores`: private tab sessions cannot be persisted.
- `oreslang-sg-stategraph.ores`: reserved END node rejected.
- `oreslang-data-science`: lower-bound clamp saturation.
- `oreslang-logic.ores`: empty conjunction and none-of identities.
- `oreslang-hpc-finance`: singleton sample variance rejected.
- `oreslang-serialization-and-validation`: rejects UTF-8 surrogate scalar.
- `oreslang-orie-ieor`: 0/1 knapsack item cannot be reused.
- `oreslang-forward-proxy-demo.ores`: double-leading slash denied.
- `oreslang-reverse-proxy-demo.ores`: safe absolute config path vs doubled slash.
- `oreslang-platform-conformance`: explicit Result fallback.
- `oreslang-source.java`: negative property assertion returns failure; plus two harness cases for field escaping and negative float tolerance.
- `oreslang-source.llvm`: i64 minimum successor.
- `oreslang-source.net`: exact integer arithmetic beyond binary64 mantissa.
- `oreslang-compilers-parity-testing`: explicit Option fallback.

## No-claim boundary
None of the 52 new cases is credited as a passing guest test merely because it was authored or committed. No private cross-organization source transfers are approved by this ledger. `steps=[]` jobs remain runner-admission failures, not tests.

## Exact-head CI observations (after second pass)
The following GitHub workflow jobs were checked at their respective PR heads. Each finished with workflow conclusion `failure`, but the selected job returned **zero executed steps**; these are runner admission failures, not evidence of failing native tests.

| Repository | Workflow run | Job ID | Executed steps |
| --- | ---: | ---: | ---: |
| `oreslang-source.java` | 41270774109 (stdlib boundary) | 124742913509 | 0 |
| `oreslang-source.llvm` | 41270720218 (LLVM compiler/native execution) | 124742756805 | 0 |
| `oreslang-source.net` | 41270725639 (.NET NativeAOT) | 124742771469 | 0 |
| `oreslang-ssr` | 41270684368 (native SSR contracts) | 124742651765 | 0 |
| `oreslang-otel` | 41270688948 (telemetry host contracts) | 124742664604 | 0 |

None of those jobs supplies compiler-backed guest evidence. Recheck head identity, runner assignment, and executed step records on a permitted source-org runner before reviewing outcomes. A different organization with free minutes is **not** by itself permission to mirror private source.

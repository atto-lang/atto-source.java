# Native Oreslang test harness rollout — 2026-10-31
Test cases: **30 new `TestCase` registrations in 20 repositories**, one `tests/test_std_harness_regressions.ores` per repository, authored in Oreslang alone.

| Repository | Draft PR | Added cases |
| --- | --- | ---: |
| `oreslang-http-routing` | [#31](https://github.com/ores-truffle-oreslang/oreslang-http-routing/pull/31) | 2 |
| `oreslang-linked-queue` | [#15](https://github.com/ores-truffle-oreslang/oreslang-linked-queue/pull/15) | 2 |
| `oreslang-otel` | [#15](https://github.com/ores-truffle-oreslang/oreslang-otel/pull/15) | 2 |
| `oreslang-rpc` | [#17](https://github.com/ores-truffle-oreslang/oreslang-rpc/pull/17) | 2 |
| `oreslang-ssr` | [#25](https://github.com/ores-truffle-oreslang/oreslang-ssr/pull/25) | 2 |
| `postgres-orm.ores` | [#14](https://github.com/ores-truffle-oreslang/postgres-orm.ores/pull/14) | 2 |
| `oreslang-web-browser.ores` | [#14](https://github.com/ores-truffle-oreslang/oreslang-web-browser.ores/pull/14) | 2 |
| `oreslang-sg-stategraph.ores` | [#7](https://github.com/ores-truffle-oreslang/oreslang-sg-stategraph.ores/pull/7) | 2 |
| `oreslang-data-science` | [#21](https://github.com/ores-truffle-oreslang/oreslang-data-science/pull/21) | 2 |
| `oreslang-logic.ores` | [#5](https://github.com/ores-truffle-oreslang/oreslang-logic.ores/pull/5) | 2 |
| `oreslang-hpc-finance` | [#19](https://github.com/ores-truffle-oreslang/oreslang-hpc-finance/pull/19) | 1 |
| `oreslang-serialization-and-validation` | [#48](https://github.com/ores-truffle-oreslang/oreslang-serialization-and-validation/pull/48) | 1 |
| `oreslang-orie-ieor` | [#4](https://github.com/ores-truffle-oreslang/oreslang-orie-ieor/pull/4) | 1 |
| `oreslang-forward-proxy-demo.ores` | [#32](https://github.com/ores-truffle-oreslang/oreslang-forward-proxy-demo.ores/pull/32) | 1 |
| `oreslang-reverse-proxy-demo.ores` | [#34](https://github.com/ores-truffle-oreslang/oreslang-reverse-proxy-demo.ores/pull/34) | 1 |
| `oreslang-platform-conformance` | [#269](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/269) | 1 |
| `oreslang-source.java` | [#2395](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2395) | 1 |
| `oreslang-source.llvm` | [#1252](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1252) | 1 |
| `oreslang-source.net` | [#279](https://github.com/ores-truffle-oreslang/oreslang-source.net/pull/279) | 1 |
| `oreslang-compilers-parity-testing` | [#15](https://github.com/ores-truffle-oreslang/oreslang-compilers-parity-testing/pull/15) | 1 |

## Contract
- These suites import the native `std/testing` module (the compiler source's own test imports the local `../stdlib/testing`). Publishing/resolving the standard-library module across consumers is a required acceptance gate, not assumed complete.
- Each suite uses deterministic, ordered `TestCase`s, `TestOptions("", false, true)`, strict `ORES_TEST` machine receipts and `require_success(rt borrow summary)`.
- Pointer-free `rt borrow` call sites are used. Canonical harness definitions and example were adjusted to `borrow T` parameters and `rt borrow` calls on this branch.
- These are **authored test cases, not passes**. A static file/registration audit (20 files/30 registrations) does not prove compilation or runtime behavior.
- At least five sampled exact-head GitHub Actions failures were `steps=[]`, so are runner admission failures, not failing test bodies.
- Compiler-backed evidence requires on-source-org exact-head identity, admitted stepful jobs, installed compiler/version identity, native guest execution of each new test, unique PASS IDs, and matching SUMMARY totals.
- Independent review and no private-source cross-organization transfer without specific authorization remain hard gates.

## Acceptance order
1. Resolve `std/testing` to the canonical stdlib implementation; compile all 20 suites at pinned compiler and repository SHAs.
2. Admit a source-org runner and confirm at least one executed step per job; otherwise do **not** count CI as a test result.
3. Execute native Oreslang guest cases and validate machine receipts, failure exit behavior, and expected counts.
4. Repeat on relevant backend profiles (Graal/JVM, LLVM, .NET, WASM where supported); report unsupported semantics separately.
5. Obtain independent review before moving any draft to ready or merging. Do not use host-only static gates as compiler/guest evidence.

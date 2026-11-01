# Native Oreslang test harness rollout — 2026-10-31
Test cases: **216 authored native Oreslang `TestCase` registrations in 20 repositories** at this snapshot, including 40 new cases from the 2026-11-01 incremental batch. Each repository has a native harness regression suite; the three compiler repositories also contain additional batch-4 suites.

| Repository | Draft PR | Added cases |
| --- | --- | ---: |
| `oreslang-http-routing` | [#31](https://github.com/ores-truffle-oreslang/oreslang-http-routing/pull/31) | 5 |
| `oreslang-linked-queue` | [#15](https://github.com/ores-truffle-oreslang/oreslang-linked-queue/pull/15) | 5 |
| `oreslang-otel` | [#15](https://github.com/ores-truffle-oreslang/oreslang-otel/pull/15) | 5 |
| `oreslang-rpc` | [#17](https://github.com/ores-truffle-oreslang/oreslang-rpc/pull/17) | 5 |
| `oreslang-ssr` | [#25](https://github.com/ores-truffle-oreslang/oreslang-ssr/pull/25) | 5 |
| `postgres-orm.ores` | [#14](https://github.com/ores-truffle-oreslang/postgres-orm.ores/pull/14) | 5 |
| `oreslang-web-browser.ores` | [#14](https://github.com/ores-truffle-oreslang/oreslang-web-browser.ores/pull/14) | 5 |
| `oreslang-sg-stategraph.ores` | [#7](https://github.com/ores-truffle-oreslang/oreslang-sg-stategraph.ores/pull/7) | 5 |
| `oreslang-data-science` | [#21](https://github.com/ores-truffle-oreslang/oreslang-data-science/pull/21) | 5 |
| `oreslang-logic.ores` | [#5](https://github.com/ores-truffle-oreslang/oreslang-logic.ores/pull/5) | 5 |
| `oreslang-hpc-finance` | [#19](https://github.com/ores-truffle-oreslang/oreslang-hpc-finance/pull/19) | 4 |
| `oreslang-serialization-and-validation` | [#48](https://github.com/ores-truffle-oreslang/oreslang-serialization-and-validation/pull/48) | 4 |
| `oreslang-orie-ieor` | [#4](https://github.com/ores-truffle-oreslang/oreslang-orie-ieor/pull/4) | 4 |
| `oreslang-forward-proxy-demo.ores` | [#32](https://github.com/ores-truffle-oreslang/oreslang-forward-proxy-demo.ores/pull/32) | 4 |
| `oreslang-reverse-proxy-demo.ores` | [#34](https://github.com/ores-truffle-oreslang/oreslang-reverse-proxy-demo.ores/pull/34) | 4 |
| `oreslang-platform-conformance` | [#269](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/269) | 4 |
| `oreslang-source.java` | [#2395](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2395) | 66 |
| `oreslang-source.llvm` | [#1252](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1252) | 36 |
| `oreslang-source.net` | [#279](https://github.com/ores-truffle-oreslang/oreslang-source.net/pull/279) | 36 |
| `oreslang-compilers-parity-testing` | [#15](https://github.com/ores-truffle-oreslang/oreslang-compilers-parity-testing/pull/15) | 4 |

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
None of the 216 authored cases is credited as a passing guest test merely because it was authored or committed. No private cross-organization source transfers are approved by this ledger. `steps=[]` jobs remain runner-admission failures, not tests.

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

## Adversarial native harness test increment — 2026-11-01

- Java/Graal #2395 adds 10 more native Oreslang cases (18 to 28), covering named empty-property diagnostics, silent property failure fallback, rejecting false/int/string assertion mismatches, newline-versus-literal-escape distinction, repeated delimiters, exact zero-tolerance float comparison, false/false boolean equality and empty receipt fields.
- These cases were **authored and exact-blob read back**, not compiled or executed by a native Oreslang compiler. At commit `5b5e107c1d98d2dda2bb796eb7a5a72122440951`, the test file contains 28 unique registrations and 28 corresponding defined test callbacks.
- Do not combine this authored-only count with Rust host `#[test]` functions, compiler-executed guest passes, or source-free canary checks. Independent review and exact-head stepful guest CI remain mandatory.

## Additional compiler test batch — 2026-10-31

- Java/Graal compiler repository [#2395](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2395): 14 additional native Oreslang harness meta/semantic cases. Covers equality/inequality, inclusive and exclusive float tolerance, first-failure property diagnostics, deterministic repeated table inputs, CR/tab and pipe machine escaping, and signed i64 boundaries. The local test imports added assertion functions explicitly.
- LLVM compiler repository [#1252](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1252): 8 additional native Oreslang signed64/precedence regression cases.
- .NET compiler repository [#279](https://github.com/ores-truffle-oreslang/oreslang-source.net/pull/279): the matching 8 signed64/precedence guest cases. Both suites must compile and run with their own compiler; matching source alone is not parity proof.
- Rust compiler [#21](https://github.com/ores-truffle-oreslang/oreslang-source.rs/pull/21): 15 Rust `#[test]` compiler-admission cases and 8 additional Rust `#[test]` cases that compile, link and execute real Oreslang scalar guests under Linux. These 23 Rust integration tests **are not Oreslang `TestCase` registrations** and must not inflate the native Oreslang total.
- Cranelift compiler [#20](https://github.com/ores-truffle-oreslang/oreslang-source.cranelift/pull/20): 5 Rust `#[test]` functions with 33 native JIT guest vectors. These also **are not Oreslang `TestCase` registrations**.

Exact-head source compilation, stdout receipts, error exits, LLVM/.NET backend module import support and peer review remain pending. The Rust scalar-only compiler intentionally does not import the canonical standard-library harness, so its Rust tests and the native Oreslang `TestCase` suite are separate evidence lanes.

**Current accounting:** 216 native Oreslang `TestCase` registrations across 20 repositories; 55 separate Rust `#[test]` functions across two Rust compiler repositories. Earlier batch counts below remain historical. A test registration is not an executed test receipt.

## New incremental batch (2026-11-01)

Added 40 native Oreslang tests (2 per repository), with exact case names and PR links in [NATIVE_TEST_INCREMENT_40_20261101.md](NATIVE_TEST_INCREMENT_40_20261101.md). After the subsequent compiler batch, the 20 tracked repositories now have **216 authored TestCase registrations**, separate from Rust host/compiler tests. The `ore.std/testing` import spelling is **not** used; the public hosted namespace is `ores.std/testing`.

## Compiler regression continuation — batch 4 (2026-11-01)

This batch adds **84 new Oreslang-native `TestCase` registrations**, all in new `.ores` files on existing draft compiler PRs. This is in addition to the earlier 132 registrations, yielding **216 authored registrations across 20 repositories**.

| Compiler PR | New native Oreslang cases | Batch-4 files | Current compiler-repo registrations |
| --- | ---: | --- | ---: |
| [Java/Graal #2395](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2395) | 36 | `tests/test_std_harness_scalar_parity_batch4.ores` (24); `tests/test_std_harness_meta_batch4.ores` (12) | 66 |
| [LLVM #1252](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1252) | 24 | `tests/test_std_harness_scalar_parity_batch4.ores` | 36 |
| [.NET #279](https://github.com/ores-truffle-oreslang/oreslang-source.net/pull/279) | 24 | `tests/test_std_harness_scalar_parity_batch4.ores` | 36 |

The 24-case common matrix tests exact signed64 boundaries, adjacent values beyond binary64 integer precision, algebraic precedence and association, booleans, floating tolerances, string comparisons, and machine-field escaping. The 12 Java-only harness meta-cases test exact-name filtering, skip accounting, fail-fast, and zero-executed suite summaries. The inner meta-suites use human-readable output so their events do not masquerade as the outer machine receipt stream.

**Additional Rust tests (separate lane, not native `TestCase` registrations):** [source.rs #21](https://github.com/ores-truffle-oreslang/oreslang-source.rs/pull/21) gained 12 new Rust numeric-separator admission tests and 8 additional Rust tests that compile, link, and execute real scalar Oreslang guests. The source.rs parser now rejects malformed decimal separators like `1__0`, `1_`, and their negative counterparts. [source.cranelift #20](https://github.com/ores-truffle-oreslang/oreslang-source.cranelift/pull/20) gained 7 Rust JIT/CLI integration functions with multiple positive/negative vectors. Totals are **43 Rust functions in source.rs** and **12 in source.cranelift** within the rollout PRs (55 combined).

**Evidence separation:** these are committed, source-backed tests; no exact-head compiler-backed guest pass has been confirmed for this batch. Any CI job with `steps=[]` remains an admission failure. The Java harness PR #2393 overlaps #2395 and requires canonical reconciliation. Do not mirror private source across organizations while the transfer hold remains active; use source-free canaries only for runner admission, then run exact-head compiler-backed checks in an authorized environment.

## Batch-4 exact-commit runner admission observations

Sampled on 2026-11-01. Each listed run is associated with the **specific commit** shown, not a subsequent moving branch. Workflow conclusion `failure` with zero returned job steps proves no native compiler test body executed in that job; runner/policy/billing diagnosis remains unresolved.

| Repo | Commit head sampled | Run / representative job | Executed job steps |
| --- | --- | --- | ---: |
| `oreslang-source.rs` | `652dbb2d96583a99787a96dec7d0db4f31580214` | 41276698230 / 124760202305 | 0 |
| `oreslang-source.cranelift` | `fea65f956b99892c87b797c9a343ae5e8497b8d4` | 41276546243 / 124759767022 | 0 |
| `oreslang-source.java` | `0ea65adcc466cd66fcabe17e9abc2edb026463ad` | 41276597309 / 124759907855 | 0 |
| `oreslang-source.llvm` | `a8fcd525029ed20a3a760000b2c4371c8a4a801e` | 41276439213 / 124759461359 | 0 |
| `oreslang-source.net` | `2f3354bf0d1c071ce3a41418e4ae8edd1f64a54c` | 41276442466 / 124759470946 | 0 |

No guest compiler pass or fail is inferred; none of these runs establishes alternative runner capacity or permits private-source mirroring.

# Reviewed source-level reconciliation: Java #2393 / #2395 and ores-llm ML #31

**Scope:** source comparison only, 2026-11-01. This document is **not** an independent approval, native compiler result, or assertion that a GitHub Actions runner executed. The branch carrying this memo is the *sole proposed Java integration branch*; do not independently merge the overlapping #2393. All three PRs must remain draft pending the gates below.

## Immutable inputs (before this memo)

| Role | Repository / PR | Exact head | Git tree |
| --- | --- | --- | --- |
| Earlier Java hardening | `ores-truffle-oreslang/oreslang-source.java#2393` | `a6a2281529f0ad5c3c7da6056eb73617c284cbcd` | `f649e8b5f7084ddadfad8b9967402f09bbe496a5` |
| **Canonical Java integration candidate** | `ores-truffle-oreslang/oreslang-source.java#2395` | `2d9177fe3afcc7136e8a89b608cb0ce9cce4eb7c` | `741f60d53a3bac510fca6dac98b34c5ca047d730` |
| Downstream ML contract consumer | `ores-llm/ores-llm-ml-conformance-tests#31` | `08024e81949a6f9a4eb56306296ce5e91ed287d4` | `605f2507862d0b15ff9377239804341b9c2f5c7f` |

The two Java heads diverge from common base `241bd67c39443cc2ef18df7e4ed456454af378c7`; #2395 is **not** a descendant of #2393. Filename and exact-blob comparison confirms all **14/14** files modified by #2393 exist in #2395: **9 byte-identical**, and **5 intentionally divergent** (`stdlib/testing.ores`, `docs/TESTING.md`, `examples/native-testing-harness-guards.ores`, `.github/workflows/ci.yml`, `.github/workflows/fast-ci.yml`). Specifically, the four negative guest fixtures, 40-case fixture, 12-scenario runner matrix, semantics fixture, normal example, and v2 design have identical blob IDs. The implementation changes in #2393's `stdlib/testing.ores` are preserved by #2395, with added machine-field escaping and a one-call PASS/FAIL event write. The #2393 `docs/TESTING.md` descriptive sections are not verbatim in #2395; this reconciliation memo preserves their testing/evidence requirements, not their wording.

### Contract and duplicate-credit decisions

- **One canonical stdlib:** use #2395's `stdlib/testing.ores` with `borrow T` signatures and downstream `rt borrow` call sites. Do not splice the older #2393 `emit_status` implementation back in: it writes split unescaped machine records.
- **Escaped receipts:** exact canonical alphabet is `\\` (encoded backslash), `\\u007c` (pipe), `\\r`, `\\n`, `\\t`, and `\\u0000`. Validate uniqueness by encoded ID, exactly one terminal SUMMARY, exact suite prefix, no duplicate PASS, no unexpected status, no hidden/malformed receipt, and exact count; reject zero-case/all-skip/all-filter and negative tests with a compiler failure but no guest summary. The current AWK script checks *format*, not authentic origin.
- **Compiler compatibility is unknown:** the `String.replace_literal` guest primitive and packaged `ores.std/testing` identity must be established by real compiler resolution. A source readback of `CoreLibrarySources.java`, `pom.xml` or guest import strings is insufficient.
- **ML #31:** 7 unique `TestCase` registrations (the 3 later tolerance boundaries are **within** these 7, not 7+3=10) in `tests/test_stdlib_contracts_20261031.ores`. These call the exported `TolerancePolicy` and `valid_tolerance_policy` with `rt borrow`. Do not credit them as the 40-case harness regression, the 12-scenario matrix, host reference tests, property table inputs, or compiler passes.
- **Cross-repo ledgers are snapshots:** do not add historical 40/84/132/155/216 totals together. Count by `(repository, exact guest source tree, suite, registered test ID)`; independently label authored, host-only, guest compiler-backed, and hardware evidence.

## Minimum authorized exact-head execution matrix

| Order | Evidence domain | Smallest check | Pass requires |
| --- | --- | --- | --- |
| 0 | Governance and identity | Exact source heads/trees above, compiler binary/source revision, stdlib blob, dependency digests, declared run attempt and policy authorization | Approval to run in an authorized trust boundary; no transfer of private source across orgs; both Java and ML `main` reported `protected=false` at review time, so branch-protection remediation is required. |
| 1 | Runner admission | Same-repository CI run for Java #2395, then ML #31 | Matching immutable head/tree, assigned runner, **nonzero** executed steps; `runner_id=0` or `steps=[]` = admission failure, never a test failure/pass. |
| 2 | Source-free parser negatives | `tests/test_ores_test_receipt_admission.sh`, `tests/test_ores_test_escape_canonicality.sh`, fast-CI exact-head contract controls | Every forged/duplicate/misordered/invalid-escape/missing-summary mutation rejected; these do not count as guest execution. |
| 3 | Compiler/stdlib smoke | Compiler-backed Java JIT guest for `examples/native-testing-harness-guards.ores`; direct canonical `ores.std/testing` import and `replace_literal` resolution | Compiler process actually runs and exits successfully on the pinned source/stdlib identity; capture command, step logs, exit, hashes. |
| 4 | First real consumer receipt | Compile/run ML #31 `tests/test_stdlib_contracts_20261031.ores` via an **approved** compiler artifact/provisioning path (not a private-source mirror) | 7 distinct exact `ORES_TEST|PASS|ml-conformance-tests/contracts/<id>|` events and exactly `ORES_TEST|SUMMARY|total=7|passed=7|failed=0|skipped=0|filtered=0`; no extra machine event; process exit 0; immutable provenance binds the receipts to compiler, stdlib, and consumer tree. |
| 5 | Negative guest / mutation | Java `native-testing-negative-{empty,skipped,filtered,failed}.ores`, plus one deliberately falsified ML guest assertion and one altered/duplicated receipt under the verifier | Executed guest negatives emit the *specific* failure summary and exit nonzero (not timeout/compile failure); altered ML guest must never pass, forged receipt must be rejected. Restore source and verify original head after mutation. |
| 6 | Backend parity and review | JIT, native AOT, and hybrid on the same canonical stdlib contract; further backends in separate contracts | Stepful nonzero runs, artifact digests, unique receipts and per-backend reports. Independent non-author review and required protection/checks before draft→ready/merge. |

**Trust boundary:** A native test must be executed **inside a permitted repository/runner environment**. Do not export private source or mirror branches to another organization while transfer hold applies, even if another organization has Actions minutes. Source-free canaries may diagnose runner admission but cannot establish compiler or guest success.

## Observed GitHub evidence at the input heads

- ML #31 `source-contract-tests` run `41276805044`: job `124760518292` had `steps=[]`.
- Java #2393 `fast-ci` run `41276479053`: job `124759573995` had `steps=[]`.
- Java #2395 `fast-ci` run `41328914069`: job `124914663069` had `steps=[]`; `ci` run `41328914005` test job `124914662827` likewise had `steps=[]`.
- No independent PR review was recorded for #31, #2393, or #2395 at source comparison. Do not claim an approved merge, a successful compiler-backed guest run, or resolved branch governance.

**Integration decision:** #2395 is the source-level **unified candidate** because it contains every #2393 changed path and preserves the fail-closed behavior while expanding receipt controls; #31 is a separate consumer source tree, not a Java cherry-pick. A reviewer must independently approve this decision on the final changed head.
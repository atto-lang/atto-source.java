# Canonical AttoLang compiler + native test harness integration

**Status: DRAFT / NOT_EXECUTED until actual exact-head compiler guest jobs run.**
This document is a review ledger for integration PR
[**#2494**](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2494).

## Source provenance and conflict decision

| Input | Verified Git commit | Role |
| --- | --- | --- |
| Java .atto admission [#2460](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2460) | `dffdd1924e0abac975cf7a35fa023b8e86e40421` | Integration branch parent |
| AttoLang native test harness [#2395](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2395) | `d156d335ddb838b7999c2ef6d9ef288ead9f8a38` | 78 reviewed file blobs overlaid |
| Shared historic base | `241bd67c39443cc2ef18df7e4ed456454af378c7` | Change-set comparison only |

The Java .atto-admission PR changed 26 paths and the harness PR changed
78 paths; there was no overlap. Comparing the Java branch directly to
the historic base yielded 107 touched paths over 392 commits, **none of
which overlapped** the harness's 78 touched paths. The integration tree
therefore copied the exact 78 harness Git **blob identities** onto the
source-admission tree. This is a **same-repository source integration,
not a merge of either original draft to `main`**.

The older common base and long-lived branches may now be behind current
`main`. Do not merge on the strength of a clean changed-path set
alone: reconcile current main and obtain review, admission and parity
evidence on the final exact tree.

## Actual implementation

- Java frontend admits canonical `*.atto` entrypoints, imports and
  linked public classes through the same checked guest compiler path,
  preserving explicit `*.ores` compatibility where required.
- `stdlib/testing.atto` is packaged by Maven. Importing
  `ores.std/testing` or `ores.std/testing.atto` selects
  `@std/testing.atto`; only explicit `ores.std/testing.ores`
  selects the old `@std/testing.ores` module. No silent downgrade.
- Six existing native test suites have **132 already-authored** unique
  canonical test IDs in reviewed ordered manifests (40+30+24+12+16+10);
  their count is not an additional test increment.
- The four deliberately failing AttoLang guests must be accepted by the
  parser/compiler before their runtime `require_success` rejection can
  be credited.
- `src/test/java/dev/oreslang/AttoNativeTestingGuestIntegrationTest.java`
  adds two genuine compiler-backed test methods: 40 exact ordered
  guest receipts, and four separately compiled/rejected negative guests.
  `AttoTestingStdlibSourceTest` adds three package/resource checks
  (not guest execution). Existing source-admission compiler JUnit tests
  contribute eight more canonical AttoLang guest cases.
- `.github/workflows/atto-native-harness-jvm.yml` runs a narrow pinned
  checkout, source-free mutation controls, GraalVM/JDK 25, the focused
  13-method JUnit cohort, 40 actual .atto guest stdout receipts,
  and four negative programs. The workflow uploads Surefire reports.
- The JDK-only `scripts/ci/AttoHarnessGuestReceiptVerifier.java`
  checks the **exact five required JUnit method identities**, counts,
  zero failures/errors/skips, XML structure, report size, and external
  entity rejection, with synthetic negative controls. The preexisting
  eight-method verifier is run unchanged. These verifiers do not
  authenticate untrusted stdout; GitHub Actions must independently bind
  artifacts to an **admitted exact-head executing runner**.

## Evidence acceptance

- `git rev-parse HEAD` equals the event's exact 40-character SHA;
  source tree SHA is logged and checkout has no untracked or staged files.
- The self-test mutations and manifest checks execute with nonzero
  recorded runner steps, not just source-only inspection.
- `mvn -B -ntp -Dtest=AttoCanonicalFixtureGuestTest,AttoImportedModuleClassGuestTest,AttoTestingStdlibSourceTest,AttoNativeTestingGuestIntegrationTest -DfailIfNoTests=true test`
  compiles and executes on Java/GraalVM; both independent Surefire
  verifiers accept the exact named methods and zero failures.
- The separate `examples/native-testing-regressions-40.atto` CLI run
  generates 40 **ordered native guest PASS events** plus the expected
  summary, validated by `verify_ores_test_receipts.sh` with trusted
  expected-case IDs.
- Each negative suite is individually compiler-admitted and actually
  reaches its real summary and expected runtime rejection; compilation
  failure, timeout, zero steps or source-free canaries are NOT accepted.
- Only after the above and independent review may native-image AOT and
  hybrid checks become merge evidence. LLVM, .NET, Wasm and
  Forge/Cranelift require independently executed guest receipts.

## Open dependencies

1. [#963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963)
   — Private GitHub Actions runner admission / zero-step failures.
2. [#2486](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2486)
   — Canonical `.atto.pkg.toml` manifest and `attolang` CLI public
   branding are **not merged here**. Those files overlap Java admission
   changes and require semantic reconciliation in a separate reviewed step.
3. Independent code/security review and branch protection before any
   private-source transfer, ready transition or merge to `main`.
4. `std/test/actor`, `std/test/clock`, virtual scheduling and
   associated actor-runtime parity are **proposed, not implemented**.

No cross-org private source mirrors, access tokens or secrets were used.

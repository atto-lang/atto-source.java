# Oreslang OTel guest parity: Java/GraalVM JIT and Native Image AOT

This backend participates in the [shared five-backend/six-profile OTel candidate contract](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/695).
The checked-in `verification/otel/backend-admission-v1.json` lists the **actual current blockers** and the source-owned `oreslang-otel` fixture/implementation Git blobs. It is intentionally **NOT_CERTIFIED**, not a capability claim.

## Current source reality

The current Oreslang-native `oreslang-otel/src/otel.ores` library targets the Java/Truffle standard library, but this source-local PR does **not** compile or execute it. `oreslang-otel/scripts/check.sh` is the source-owned Java guest smoke path and requires a pinned, clean `SOURCE_REF` checkout. JIT and full Native Image AOT require **separate** real guest processes and receipt paths.

## Required compiler-backed integration

Run the unchanged smoke fixture under a pinned Java/GraalVM JIT compiler, then the same source under a **real GraalVM Native Image** guest. Verify source extension/import compatibility, `process.monotonic_ns`, `process.unix_ms`, integer/Unicode JSONL encoding, actor-safe nonblocking stdout, final flush barriers and shutdown retirement. Java PR #2353's async stdout must be reviewed and executed, not assumed merged. Keep `CoreLog/CorePerf` operational diagnostics separate from application logs.

## Fail-closed source-owned admission

```sh
node verification/otel/verify-backend-admission.mjs --self-test
node verification/otel/verify-backend-admission.mjs --require-executed-guest
```

The **first** command tests the source-only capability manifest and fourteen hostile promotions; a green result reports `SOURCE_ONLY_CANDIDATE`, **never guest parity**. The **second** command must fail until a separate reviewed real compiler/guest integration and independent artifact corroboration exists. The source-local `otel-guest-admission.yml` checks both behaviors with pinned checkout, exact original PR-head assertion and no external private source checkout.

A real candidate must preserve the original `oreslang-otel/tests/smoke.ores` file and `src/otel.ores` import source at the approved Git blobs, execute the correct **backend compiler and runtime**, and provide raw stdout for `bin/verify-otel-guest-parity.mjs` in the separate conformance checkout. The checker requires six log records, two correlated completed spans, one metric, and recorded compiler/artifact/stdout digests. Even a passing checksum and schema is **self-reported evidence** until the independent trusted job auditor confirms build/run provenance and nonzero executed steps.

## Delivery and release gates

- A status check that ran zero steps is **NOT_EXECUTED**; host-only manifests, fixture emitters, test sinks and static documents are never promoted to compiler guest evidence.
- Telemetry must be actor-local and explicit, with immutable IDs across actors, bounded nonparking output, positive or explicitly reported backpressure, proper finalization and credential/PII redaction.
- `ores_trace_id` is opaque and `oreslang-otel.v1` JSONL is **not OTLP**. A separate explicit mapping to standard OTLP trace/span IDs and W3C propagation is required before exporting to the ORES OTEL collector.
- Collector staging [PR #13](https://github.com/ores-otel/ores-otel-monorepo/pull/13) is a non-production receiver. Independent real exporter/storage query readback, workload auth and multi-tenant isolation are separate release gates.
- Keep this PR **draft** until real guest execution and independent review; do not copy private source to an unapproved public/private carrier, and do not add Python scripts or dependencies.


## Current-head JVM + Native Image execution candidate (2026-11-11)

This PR now also contains a **real, opt-in compiler/guest execution lane**:
`.github/workflows/otel-java-real-guest.yml` with independent `otel-jvm`
and `otel-native-image` jobs. The source-owned runner is
`verification/otel/execute-pinned-smoke.sh`.

The private original OTel library, smoke fixture, Rust oracle code, Rust
Cargo manifest and locked dependency graph were copied **only into this
private repository in the same source organization** under
`verification/otel/pinned/`. Every copied file's Git blob SHA was checked
against the original `oreslang-otel` path using the GitHub connector:

| Canonical original | Git blob SHA |
| --- | --- |
| `src/otel.ores` | `6a1b9b940f10e4b273ae2b02be5cfff5e0572afc` |
| `tests/smoke.ores` | `c22a8841b868cd89dd4cb720435d9585fbbb05cc` |
| `tools/telemetry-check/src/main.rs` | `2f91087449a58e7b5756d23868860dbcb82e4847` |
| `tools/telemetry-check/Cargo.toml` | `bb007125dff7b207f34acdde22f91f61283db256` |
| `tools/telemetry-check/Cargo.lock` | `cc5a82761a5188f1a2ed4f9ec67e566ddfed7f45` |

The branch builds the **actual** Java/GraalVM compiler with Maven from the
checked-out PR head, invokes `dev.oreslang.launcher.OresMain` with the
unaltered smoke program, and runs the pinned **Rust** `--check-smoke-output`
checker against captured raw stdout. It records compiler tree/PR SHA,
original library/fixture Git blobs, stdout and executable hashes. JVM
receipts additionally bind the built application JAR and runtime classpath
manifest. The independent Native Image job builds with existing Maven
`-Pnative-aot` (**`--no-fallback`**), checks actual ELF and
`--build-info` (`build_mode=aot`, `guest_execution=interpreter-no-jit`)
and executes the same original guest.

**AOT distinction:** This is a GraalVM **native AOT host** with an
interpreter-only Oreslang guest. It is **not evidence that the Oreslang guest
itself was lowered to standalone ahead-of-time machine code**.

Only bounded synthetic guest output, build info and a **non-certifying** receipt
are uploaded with seven-day retention; no private compiler source, binaries,
secrets or runtime stderr are uploaded. Import resolution is preserved by the
snapshot's original `tests/../src/otel` layout. The snapshot must be updated
with independent review if upstream guest or oracle files change.

The workflow requires a fresh, exact original PR-head checkout, JDK 25 /
GraalVM 25.3 and Rust 1.95. The local script requires
`EXPECTED_SOURCE_SHA=<actual pristine checkout HEAD>`, a built target
compiler, and appropriate `JAVA_HOME`. No launcher stubs or cached guest
stdout are accepted as proof. To run locally in an approved clean checkout:

```sh
mvn -B -ntp -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
EXPECTED_SOURCE_SHA="$(git rev-parse HEAD)" bash verification/otel/execute-pinned-smoke.sh jvm

mvn -B -ntp -Pnative-aot -DskipTests package
EXPECTED_SOURCE_SHA="$(git rev-parse HEAD)" bash verification/otel/execute-pinned-smoke.sh aot
```

**Evidence status is still NOT_EXECUTED on source-org GitHub Actions.** Both
jobs in [run 43068540133](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43068540133)
failed before acquiring a runner (`steps=null`, `runner_id=null`).
No real guest process was reported as executed. No private compiler source
has been copied outside the source organization. The source repository's
`.github/private-ci-approved-repositories.txt` requires independent
authorization for private cross-org carrier mirrors; its proposed candidate
is not authorization for an unreviewed transfer. Do **not** bypass it just
because the source org has exhausted Actions budget.

### Promotion blockers remaining

1. An independently authorized runner/CI budget with actual nonzero executed
   steps for each exact-head JVM and Native Image job.
2. Real compiler + guest success with original Rust semantic oracle and its
   negative controls; capture actual raw outputs, source trees and binary IDs.
3. Actor-safe bounded nonparking stdout/shutdown behavior. The current library
   uses synchronous `stdio.println` and does **not** establish the async gate.
4. Explicit Oreslang-to-standard-OTLP mapping, tenant authorization and
   persisted exporter/query readback. Local `oreslang-otel.v1` JSONL is
   **not** OTLP.
5. Actual standalone compiled Oreslang guest AOT if required beyond the
   interpreter-only native host verified by this workflow.

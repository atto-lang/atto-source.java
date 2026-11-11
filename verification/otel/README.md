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

The **first** command tests the source-only capability manifest and eleven hostile promotions; a green result reports `SOURCE_ONLY_CANDIDATE`, **never guest parity**. The **second** command must fail until a separate reviewed real compiler/guest integration and independent artifact corroboration exists. The source-local `otel-guest-admission.yml` checks both behaviors with pinned checkout, exact original PR-head assertion and no external private source checkout.

A real candidate must preserve the original `oreslang-otel/tests/smoke.ores` file and `src/otel.ores` import source at the approved Git blobs, execute the correct **backend compiler and runtime**, and provide raw stdout for `bin/verify-otel-guest-parity.mjs` in the separate conformance checkout. The checker requires six log records, two correlated completed spans, one metric, and recorded compiler/artifact/stdout digests. Even a passing checksum and schema is **self-reported evidence** until the independent trusted job auditor confirms build/run provenance and nonzero executed steps.

## Delivery and release gates

- A status check that ran zero steps is **NOT_EXECUTED**; host-only manifests, fixture emitters, test sinks and static documents are never promoted to compiler guest evidence.
- Telemetry must be actor-local and explicit, with immutable IDs across actors, bounded nonparking output, positive or explicitly reported backpressure, proper finalization and credential/PII redaction.
- `ores_trace_id` is opaque and `oreslang-otel.v1` JSONL is **not OTLP**. A separate explicit mapping to standard OTLP trace/span IDs and W3C propagation is required before exporting to the ORES OTEL collector.
- Collector staging [PR #13](https://github.com/ores-otel/ores-otel-monorepo/pull/13) is a non-production receiver. Independent real exporter/storage query readback, workload auth and multi-tenant isolation are separate release gates.
- Keep this PR **draft** until real guest execution and independent review; do not copy private source to an unapproved public/private carrier, and do not add Python scripts or dependencies.

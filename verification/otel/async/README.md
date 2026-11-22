# Actual native Oreslang asynchronous telemetry integration (draft)

This is a **real guest-execution gate** for Java/GraalVM async stdio:
`.github/workflows/otel-async-native-guest.yml` builds the actual private
Java compiler and executes two unchanged native `.ores` programs under
both JVM/JIT and a GraalVM Native Image ELF host. It never emits synthetic
guest output or substitutes source-only checks for compiler tests.

The source assets were copied **only inside the same private
`ores-truffle-oreslang` organization**, from
`oreslang-otel` draft PR #5, with independently checked Git blob
equality immediately after creation:

| Canonical original file in oreslang-otel PR #5 | Git blob SHA |
| --- | --- |
| `src/otel.ores` | `0f53db094ed9794b271b341aa889cb51656b4812` |
| `tests/smoke.ores` | `6480413e7a6f8b6bef1a8a279f777f8293dc9937` |
| `tests/span-finish-admission-retry.ores` | `7d72f983cf09ede66ea8a62c2e17e60f7b03f6ba` |
| `tools/telemetry-check/src/main.rs` | `baee942bd7860cd327d34b6c4ab78c03f21cd0ba` |
| `tools/telemetry-check/Cargo.toml` | `55fbd9508b735d0248c80339234cd82eb533275a` |
| `tools/telemetry-check/Cargo.lock` | `cc5a82761a5188f1a2ed4f9ec67e566ddfed7f45` |

`verification/otel/async/execute-async-otel-guest.sh` rechecks all
six source blobs, the exact checkout SHA, clean non-build sources, guest
process exit 0, bounded real stdout/stderr, and the pinned **original
Rust semantic checker**. It executes the actual guest via the Java OresMain
launcher or `target/ores-aot` native ELF, never via a fixture generator.
Separate receipts bind smoke and span-retry execution to their output hashes,
loaded JVM bytecode/dependency JARs or native ELF, and original root Git tree.

The new `span-finish-admission-retry.ores` guest exceeds the async
writer's 65,536-byte record limit deterministically, catches admission
rejection, retries completion on the same actor-local span, verifies
idempotence, then **awaits `stdio.flush_async_logs()`**. The separate
Rust checker requires **exactly one** valid span completion, the expected
parent/name/trace/outcome/status fields and no post-finish records.
Source code in OTel PR #5 was fixed to set `ended=true` only after
successful async queue admission. Admission is **not** physical durability;
a writer can still fail after enqueuing.

The `guardrails.sh` helper is identical to Java OTel PR #2876's SHA
`f0ff0cd1c8068f44603bef556381a56f4835fd19`.
The location-adapted Bash negative suite
`test-guardrails.sh` Git blob is
`5a4b551986033ec8b33042db404d95a594654e9b`.
Both were executed on local Linux, with the exact Git blob matches
verified: class/JAR mutation, native ELF/managed launcher spoofing,
symlinks, nonzero exits, 1 MiB stdout/stderr ceilings and stale output
rejection. **These are source-only harness tests, not guest compilation.**

## Required execution

With an approved exact-head private runner, GraalVM 25.3 / JDK 25,
Maven, Rust 1.95 and native-image, the GitHub workflow executes:
- Rust oracle unit/negative cases
- real async-stdio / actor-lifecycle focused JUnit tests on JVM
- actual Maven source compiler build and classpath resolution
- unchanged native `tests/smoke.ores` (6 logs, 2 spans, 1 metric)
- native `tests/span-finish-admission-retry.ores` (oversized reject and
  exactly one accepted completion)
- the original Rust output-only semantic checker against captured guest stdout
- a separate native-host ELF + `--build-info` execution with matching tests

For local approved clean checkout after building the compiler:

```sh
EXPECTED_SOURCE_SHA="$(git rev-parse HEAD)" bash verification/otel/async/execute-async-otel-guest.sh jvm
EXPECTED_SOURCE_SHA="$(git rev-parse HEAD)" bash verification/otel/async/execute-async-otel-guest.sh aot
```

**Release gate:** No actual guest/JUnit/Native Image execution has been
obtained on this exact source head. The source org's GitHub private Actions
jobs have repeatedly failed before runner assignment. External private
source mirroring remains prohibited pending independently reviewed
protected-source and protected-carrier approval (Java issues #963, #1501).
The private source was NOT moved to the unrelated external carrier.
Keep PR #2353 and OTel PR #5 draft; do not merge as proven parity.

The Native Image build produces a native **host** with an interpreted guest.
GraalVM 25.3.4.1 warns that `--no-fallback` is deprecated/no-effect, so this
gate verifies actual ELF, dynamic loader exclusions and runtime build-info
instead of relying on that flag. It does not prove independently compiled
Oreslang guest AOT or OTLP exporter/collector integration.

## 2026-11-11 strict JSON and fail-closed receipt hardening

The source-owned Rust checker now rejects duplicate JSON object member names
**recursively**, including nested maps/arrays and escaped-equivalent keys
(e.g., `"\\u0073ignal"` vs `"signal"`). Previously, direct decoding to
`serde_json::Value` could silently overwrite duplicate properties. Explicit
`serde` is now locked as a direct dependency, and regression tests cover
duplicate top-level/nested keys alongside valid UTF-8, strings and signed-i64
records. The original OTel source and Java-pinned checker blobs were compared
after copying and matched byte-for-byte (see table above).

`execute-async-otel-guest.sh` now also clears receipts before each invocation
and removes any partial receipts on failure, including a first-fixture success
followed by a second-fixture or final compiler artifact hash failure.

**Actually executed locally:** Linux `bash -n` and the
`verification/otel/async/test-guardrails.sh` negative suite on exactly these
GitHub blob IDs:

- `guardrails.sh`: `f0ff0cd1c8068f44603bef556381a56f4835fd19`
- `test-guardrails.sh`: `5a4b551986033ec8b33042db404d95a594654e9b`
- `execute-async-otel-guest.sh`: `d43965ad1cc8838f14d84aa00bac41797a45ef3d`

The new receipt test creates a disposable local Git checkout with deliberately
missing pinned guest source, injects stale success receipts, invokes the
**actual guest launch script**, verifies admission is denied and checks that
both stale receipts are removed. The tests passed. They run only harmless
source-free processes: **no real Oreslang compiler, guest, Maven, Native Image
or Rust Cargo checker has been executed on this exact PR head**.

The latest exact-head [private Actions run #43104211353](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43104211353)
still failed before any runner was assigned (prerequisite job
`runner_id=null`, `steps=null`; JVM and AOT jobs skipped).
Do not promote source-only test coverage or the stricter oracle itself to
real guest parity evidence.

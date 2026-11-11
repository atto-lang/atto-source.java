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
| `tools/telemetry-check/src/main.rs` | `9d692d836552736fb3e990a1a8257cad9a660434` |
| `tools/telemetry-check/Cargo.toml` | `f9c9779bd3a895f096ce013819087c059fcc6a48` |
| `tools/telemetry-check/Cargo.lock` | `f857130281cc51f23b0c5d9ef72ffa2882402be1` |

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
`d3d2d02ba7d3aa5303c158e7bc5e4226d4c2ac02`.
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

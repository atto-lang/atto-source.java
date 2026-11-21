# CounterLive Java host actor-turn verification and guest blocker

Status: **test-only Java host oracle; NOT a compiled Oreslang guest**.

Java PR #2410 already contains a real TCP/WebSocket -> ActorRuntime mailbox JUnit
test. Its event callback previously issued hardcoded patch/ACK constants.
This change drives the callback through CounterLiveTurnModel, which has
revision checks, authenticated replay fingerprints, bounded deduplication,
non-reentrant turns, and no output after retirement.

Run `bash verification/liveview-counter-host/run.sh` with stock JDK 21+.
It compiles the exact test-only Java model with -Xlint:all -Werror, and runs
332 host assertions without Maven, network access, or runtime reflection.

**Not yet verified:** full Java JUnit/Maven against the actual ActorRuntime,
JDK25/GraalVM Native Image, compiled CounterLive.atto guest, and any
LLVM/Wasm/Forge/.NET compiled guest. An independent source-level actor
WebSocket capability is still needed: opaque generation-fenced handles
and a scoped NET permission, with no Java Socket or host object exposed.

Source-only canaries and a private runner with unrelated successful jobs
do not count as guest execution. Preserve the open private-source export
incident and all review/branch-protection gates; do not mirror to another
organization or merge on the strength of host tests alone.

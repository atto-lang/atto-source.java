# CounterLive `.atto`: exact compiler-admission gate

## Scope and test identity

The fixture at `src/test/resources/atto/liveview/counter_live.atto` is
byte-for-byte identical to `liveview/guest/counter_live.atto` on the Phoenix
LiveView PR #17 head checked on November 4, 2026. Git blob:
`242168d28b84d7380eec5a4fcf9aa98695de3b74`.

`CounterLiveAttoCompilerGateTest` exercises the **real** Java backend's
`LinkedProgramRunner.validate` API and the real `OresMain --check` CLI path. A valid result must identify the exact
`.atto` source unit. Negative tests first validate an **unmodified positive control at the identical
source path**, and only then mutate that source and require rejection. This
prevents an unsupported parser, missing configuration or bad temp path from
masquerading as sealed-state enforcement. The checks include direct state reads,
direct writes through `self`, and illegal writes under `critical-read`. The
CLI denial requires exit status **1**, not merely any nonzero usage failure. There is no exception,
feature-flag, host mock, or fallback that silently marks unsupported syntax
as supported. Unsupported actor syntax should cause the test to fail.

Run on an approved exact-head private source runner with JDK 25 / GraalVM:

```
mvn -B -ntp -Dtest=dev.oreslang.launcher.CounterLiveAttoCompilerGateTest test
```

## Limits and next promotion gates

The above is **compiler validation only**. It does not execute the guest or
prove WebSocket authority, actor state transitions, supervision, a network
listener, five-backend parity, GraalVM Native Image, or successful hosted CI.
It must not be substituted with the existing Java `CounterLiveTurnModel`,
Node.js host reference, synthetic provenance receipts, or a zero-step CI job.

After this check passes, implement and independently execute: (1) `.atto`
actor spawn + typed event-to-mailbox dispatch, (2) real bounded WebSocket
admission/close/retirement and event-to-patch/ACK, (3) GraalVM Native Image
compilation and runtime test, (4) matching LLVM, Wasm host-adapter,
Forge/Cranelift, and .NET Native AOT guest tests with exact source/fixture
hashes. The private-source export hold in conformance incident #22 remains
in force; do not mirror this repository into another organization or alter
branch protection to work around missing Actions minutes.

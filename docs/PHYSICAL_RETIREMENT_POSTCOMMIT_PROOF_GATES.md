# Physical-retirement postcommit proof gates (draft, issue #1944)

This document is a **blocking proof-gate audit**. The two original red counterexamples now have production fixes on this branch, but merge still requires an executing exact-head JUnit/native verification result.

## Independently verified source-level counterexamples

**A. Failure after physical retirement was committed.** The trusted finalization handoff may synchronously call `ActorPhysicalRetirementRegistry.retireVerified()` and then throw. `retireVerified()` irreversibly flips `retired`, while the caller catches the exception and latches `firstFailure` only afterward. This produces `retired && firstFailure != null`; there is no legitimate retroactive quarantine. A thread-identity reservation prevents a foreign STOP caller from winning the handoff, but **does not** prevent the authorized handoff from doing fallible work after commit.

**B. Native callback stack still active after Java delegate returns.** The production `ActorPhysicalIoCallbackLease.completed` currently calls `completeAfterPhysicalRelease()` before the enclosing provider callback invocation has necessarily returned. A delegate's Java return and even a successfully completed host retirement observer do not prove the provider has released its native/FFI callback loan. A physical-driver-owned exit witness is required, not a cancellation state or an optimistic callback-counter decrement.

These are separate gates: fixing A does **not** fix B.

## Required implementation protocol

1. Admit obligations before resource publication and make shutdown admission atomic with the owner actor's lifecycle fence. Use registry + incarnation + typed obligation identity, no wrapping identity reuse.
2. The driver retains a physical obligation until a **driver-specific physical-exit witness** has been issued. For NIO provider or FFI callbacks, the witness must come from code outside the delegated user `CompletionHandler` invocation; otherwise fail closed.
3. All **fallible** host and terminal observers are counted until they exit. Their failures become sticky before the fence is dropped.
4. A separate trusted finalization stage performs **fallible precommit** work first (close scheduler, release hooks, wait observers, decide quarantine), then publishes a provably nonthrowing, terminal DONE operation as the **last** externally visible action. No callback may throw after DONE, no cleanup continues afterward.
5. A rejected wake/failed physical loan enters quarantine, never `RETIRED`. Accepted-but-never-executed wake cannot prove liveness; it must keep the actor pending.
6. A supervisor parent cannot report reclaimed before every child incarnation and every child-issued physical obligation is retired. Cross-generation ACKs cannot affect successor instances.
7. Preserve layout independence: AoS, SoA, AoSoA, SoAoS and ECS may arrange lifecycle metadata differently but may not duplicate authoritative obligation ownership or weaken generation checks.

## Evidence policy and execution

The `ActorPhysicalRetirementPostcommitProofTest` now encodes the repaired contracts. A focused JDK 21 harness matching the project's Java release has executed the staged-retirement and provider-exit state machines successfully, including early/duplicate witness failure cases. This is not a substitute for the repository's exact-head JUnit suite, GraalVM Native Image, LLVM parity, or model checking.

Acceptance requires a green exact-head JDK25 Maven/JUnit run, actual native-image and LLVM parity checks, and an independently executed Rust oracle/TLA+ state check. A GitHub Actions failed job with `steps=[]` is an observed no-step failure, not a test failure. Do not merge this branch merely because the PR appears mergeable.

**Security:** no cross-organization private-source mirroring, test-org branches, or workflow dispatch. Unused GHA minutes and private destination visibility do not authorize transfer. Any future private source distribution requires independently reviewed, merged, protected, destination-specific policy and security incident resolution.

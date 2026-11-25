# Supervisor-of-supervisor automatic subtree containment (draft v1)

**Status: opt-in host substrate, not merged or compiler-enabled.**
Stacked on Java #2773, which depends on #2765, #1965, #1962, #1924 and #1911.
Formal companion: [conformance #659](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/659).

## Recovery architecture

`SupervisorEscrowSubtreeRecoveryV1` uses the existing `SupervisorFailureEscrowV1` and `SupervisorFailureEscrowRuntimeBridgeV1`. **There is no new terminal-notice ledger or competing ACK path.** The shared ledger remains the authority for actor identity, generation, supervision parent, admission fence and pending CONTROL notices.

The trusted host installs exactly one recovery coordinator on the bridge before starting supervised actors, supplies a **separate, queued CONTROL executor**, and attaches runtime-minted ActorRefs in supervision-tree order. Guest code cannot call the package-private host APIs, mint CONTROL deliveries, access other actors' sealed state, or acknowledge a terminal notice.

On failure, `ActorRuntime` records the terminal cause and the bridge atomically fences the affected supervision subtree **under the existing actor lifecycle lock**. It then *only queues* a failure ID for CONTROL processing. The CONTROL executor traverses the ledger's tree of registered actor refs, deepest-first, and issues nonparking `runtime.cancel(ref)` for each not-yet-DONE node. It does **not** use host `stop()`, run guest supervisor hooks inline, or treat a failed cancellation attempt as a cleanup receipt.

Clean supervisor exit and failures that first appear in final cleanup also queue descendant retirement. An unrelated guardian-owned sibling is not cancelled. A queue rejection, inline executor, unauthorized actor/mailman CONTROL callback, or failed cancellation leaves a **sticky CONTROL fault**; restart is blocked instead of pretending the subtree was safely recovered.

## Restart safety

This draft adds **manual, host-only top-down replacement with strict checks** and an explicit opt-in permanent ONE_FOR_ONE admission policy for a guardian child. It is NOT automatic restart orchestration.

`restartBlocker(nodeId)` refuses to issue a permit while CONTROL is faulty, the node still lives, any supervised descendant is not logically DONE, any failure notice is unacknowledged, the parent has not been replaced into a live generation, or a separately supplied **native/FFI physical-release verifier** rejects any affected actor. Java `done()` is insufficient to pass that final gate.

`reserveRestart` issues a unique process-local permit for one old incarnation and rejects duplicate reservations. `attachReplacement` consumes the permit, requires a fresh monotonic ActorRef generation, the exact current live parent, and reserves an incomplete successor slot **before** actor registration to prevent competing replacements. A registration failure leaves the old incarnation blocked for explicit host repair (fail closed). New actor IDs never recycle old terminal-notice identities.

The release verifiers in the synthetic-oracle JUnit tests is intentionally `ref -> true`, a **synthetic test oracle**. It exercises generation logic only. No production physical-release oracle exists in this draft, so do not enable restart in production based on Java DONE or a caller-supplied boolean.

### Opt-in restart circuit integration (wave 10)

`attachRestartManagedRoot(ref, config)` explicitly binds a single-child restart circuit to a guardian child before activation. The exact circuit source is copied from Java #903, not a separate implementation. This scope is permanent ONE_FOR_ONE; it is **not a shared budget across siblings**, and nested manual recovery remains unchanged.

Only after terminal ACK and independently authenticated physical retirement does `reserveRestart` admit and charge the episode once. The returned single-use permit retains the exact old generation. `restartDelayRemainingNanos` is a nonblocking scheduler input; it does not start a timer. `attachReplacement` independently enforces elapsed backoff, generation and parent authorization before consuming the permit, then carries the same policy to the successor. Failed attachment leaves the old successor slot permanently fenced. Escalation blocks every subsequent reservation even after budget expiry.

The added real ActorRuntime test uses an injected monotonic clock across signed wrap and a synthetic physical-release oracle, tests early denial without permit loss, successor budget exhaustion and sticky escalation. It does not certify native/FFI physical release. Timer scheduling, automatic parent recovery, group-wide budgets, declaration-ordered strategies, and integration with the canonical production CONTROL lineage remain outstanding.

## Executable regression inventory

- Previously executed (host-only): standalone JDK 21, `javac -Xlint:all -Werror`, 557 passing assertions on exact Git blobs `6934a266090a16361154a7fa24859d99c46a6470` (escrow) and `bfa4618c1e76041c8e5a90ad4950abdef73e1602` (test).
- New coordinator: compile-only under JDK 21 against **minimal stub ActorRuntime interfaces**, not a full source tree compile. GitHub blob verification pins the actual committed coordinator `18463efa99db15d3ff4b0ec650214add03747375`, bridge `55f4feb55c4892c43612dbcfffe1127666299750`, full ActorRuntime `0760e04d1ccc57828317aebe9416324e08185b34`.
- Real runtime JUnit: **8 tests authored, NOT EXECUTED**. The parent suite's obsolete `acknowledgeCommitted` call was fixed. New cases cover nested automatic cancellation, unaffected sibling, pending-failure/release denial, synthetic restart permitting, duplicate-permit denial, three-level top-down replacement, stale ActorRefs, and rejecting an inline CONTROL executor.
- JDK25 exact-blob workflow now requires both suites' Surefire XML (`3 + 5` cases, zero failures/errors/skips) and the pre-existing 557 standalone assertions. A zero-step Actions failure is **NOT_EXECUTED**, not a proof of correctness.

## P0 release gates and known limitations

1. Stepful exact-head full-source Maven/JUnit, ASan/UBSan where applicable, JDK25 and GraalVM Native Image execution; troubleshoot any semantic failure before promotion.
2. **Reconcile terminal ledger lineages**: Java #1222 and #1320 have independent stronger pre-reserved CONTROL mechanisms. Do not merge multiple active ledgers/ACK authorities. This experimental bridge stays off by default until one canonical implementation is chosen.
3. Connect trusted CONTROL scheduling to the production supervisor `SupervisorShutdownTree` / `SupervisorProcess` decision commit fence and real child/group ownership. This draft only coordinates opt-in policy parentage. It does not prove all executor work was admitted to a single runtime-wide CONTROL lane.
4. Add genuine native/FFI/heap/stack/borrowed-loan release receipts; no `PhysicalRelease` event may be synthesized from Java `done()`. Verify no accepted in-flight DATA or nested actor escape during failure fencing.
5. Extend the opt-in single-child restart budget/backoff to shared supervisor budgets, automatic timers, one-for-all/rest-for-one policy, supervisor policy hooks, and source-level `define supervisor` compilation with actual guest semantics.
6. Run TLA+ TLC for conformance #659 and compare actual Java transition traces. Then independently certify LLVM, .NET NativeAOT, Wasm and Forge. A full in-process guardian crash requires an OS guardian and crash-consistent WAL, not this prototype.

This branch MUST remain DRAFT / NOT_CERTIFIED. No cross-organization private-source mirror, hidden CI bypass, or source-main mutation is authorized by the code.

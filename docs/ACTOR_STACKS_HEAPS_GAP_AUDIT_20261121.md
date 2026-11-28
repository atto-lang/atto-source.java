# Actor stacks and heaps: gap audit against atto-spec (2026-11-21)

Status: **DRAFT audit.** Done by reading source and PR descriptions only.
Nothing was built or executed for this document, so every status below is
**NOT EXECUTED** unless it quotes another PR's recorded evidence.

This document adds **no new design and no code**. The language spec already
decides the model:

- `atto-spec` chapter 05 makes ordinary actors concurrent by default.
- Chapter 22 adds the concurrency/shared-ownership amendment.
- Chapter 18 sets stack containment rules 1–8.
- Chapter 25 gives every live receive invocation and every child Task its own
  logical stack.
- Chapter 27 gives each actor incarnation one u32-offset arena heap.

The job here is to map each normative requirement to its current state and to
the open PR that already covers it, per the org's merge-first rule (finish
existing PRs, don't duplicate them). It replaces the unsubmitted legacy draft
`ores-truffle-oreslang/oreslang-source.java@docs/actor-stacks-heaps-audit-20261116`.
That draft was written before #3156, #3164 and the spec chapters above were in
view. Its "parallel mode is opt-in" framing is **wrong**: the spec makes
concurrency the default.

Audited heads:

| Repo | Commit |
|---|---|
| atto-spec | `82ae5a323a` |
| atto-source.java | `12ded133d8` |
| atto-source.llvm | `fe4f253b59` |
| atto-source.wasm | `16ba74ab9f` |
| atto-common-source | `3bdb4eda78` |

## 1. Summary

| Area | Java | LLVM | Wasm |
|---|---|---|---|
| Logical stack per receive invocation (ch25) | **Yes**: `LogicalStackQuota` (1,024 frames, 64-frame unwind reserve, per-carrier ceiling) | Substrate only, unwired; competing PRs #689/#690/#741 | Managed-frame PRs #188/#192 |
| Stack exhaustion contained (ch18 rule 7) | **Regressed**: recursion now runs out of JVM heap first (#1666) | **No** on `main` (SIGSEGV/`abort()`); tenant-VM #716 | Spike only; `aot-host` has no `max_wasm_stack` |
| Concurrent-by-default actors (ch05/22/25) | **No**: defaults `1/1`, rejects `>1`, one suspended receive | Scheduler lane is boolean | No |
| Per-Task stack (ch25) | **No**: Tasks share the carrier root segment | No | No |
| Per-kind stack quota (ch18 rule 5) | **No**: global `ores.stack.segmentFrames` only | #716 (tenant VM) | Per Store |
| Aggregate per-actor stack accounting (ch25) | **No**: per-segment only; heap-held frames uncharged (#1666) | #741 | No |
| One u32 heap per incarnation (ch27 §1) | **Yes** for the arena (`ActorNativeArena` at spawn) | Scalar i64 heap; u32 arena ABI only; slab heap #816 merged | Native (linear memory) |
| Guest payload bytes in the arena (ch27 rule 4) | **No**: the evaluator allocates JVM objects; slab heap #1724 is a draft | Partial | Yes |
| Arena misuse fails only the actor | Yes (exceptions) | **No**: `deny()` calls `abort()` | Trap |

## 2. Java (`atto-source.java`)

### 2.1 Stacks

**Done on `main`:** `LogicalStackQuota` / `LogicalStackOverflowException`,
documented in `docs/ACTOR_LOGICAL_STACKS.md`. It provides:
- a 1,024-frame segment per receive invocation;
- a 64-frame unwind reserve;
- a per-carrier ceiling derived from the real thread stack, with up to 1 MiB of
  host reserve;
- 16 MiB carriers;
- proper tail calls inside the `Evaluator.invoke` trampoline;
- stack exhaustion that guest `catch`/`trap` cannot consume, and that is not a
  `VirtualMachineError`, so only the owning actor fails.

This meets ch18 rules 1–3 and 6–7, and the ch25 unwind table, **for
synchronous call chains**.

| # | Gap against spec | Evidence | Covered by | Disposition |
|---|---|---|---|---|
| J-S1 | **Recursion bypasses the quota and runs the JVM out of heap.** Recursive cycles became SourceTask boundaries, and each one restarts at depth 0. | #1666 body: bisected to legacy #2657. 4 of 6 `ActorLogicalStackOverflowTest` methods OOM and stop the surefire fork. | **#1666** (draft) | **Highest priority.** This is a live containment regression, and it hides about 1,300 tests after the fork dies. Land #1666 first. |
| J-S2 | Source Tasks share the carrier's implicit root segment. ch25 requires a distinct stack per child Task. | `ACTOR_LOGICAL_STACKS.md` "Limits" | none found | **New work:** add a per-Task `Segment` at the `OresScheduler` step boundary. |
| J-S3 | There is no per-actor-kind quota (ch18 rule 5). | `ores.stack.segmentFrames` is global, and `LogicalStackQuota` has no reference to actor kind | none found | **New work:** add `segmentFrames` to `IsolatePolicy` / actor-kind configuration. UNTRUSTED should be smaller. |
| J-S4 | There is no aggregate per-actor accounting across live invocations, Task stacks and heap-held continuation bytes (ch25 "Budgets"). | Accounting is per segment only | #1666 charges heap-held call frames | **Extend after #1666:** add an actor-wide budget that also covers J-S2's Task segments. |
| J-S5 | Receive CALL frames are not yet bound to invocation-owned typed frames (ch27 rule 8: frames are actor-owned storage). | #1488, #1454, #1455, #1503 | **#1488**, #1454/#1455/#1503 | Existing stack, independent of J-S1. Reconcile with #1666 before merging (both change frame charging). |
| J-S6 | Extreme expression nesting inside one frame can still cause a host `StackOverflowError`, which is handled as VM-fatal. | `ACTOR_LOGICAL_STACKS.md` "Limits" | — | **Allowed** by ch25 ("MUST NOT promise recovery from arbitrary host … overflow"). Parser nesting limits bound it. No action. |

### 2.2 Concurrency (spec ch05:39, ch22, ch25 "Concurrency")

The spec says ordinary actors are concurrent by default, with up to a bounded
per-actor live-invocation limit, and `define serial actor` admits exactly one
live invocation. Java on `main` is serial for every actor:

- `Parser.java:1610-1611` defaults `requestConcurrency = 1, messageConcurrency = 1`.
- `TypeChecker.java:1710` rejects concurrency above 1.
- `ActorRuntime.java:3528` allows only one suspended source receive per actor.
- `ActorCell.beginTurn` rejects concurrent `activeTurns`.
- `docs/formal/ACTOR_EXCLUSIVE_CARRIER_PROOF.md` proves `count(carriers) <= 1`
  for **all** actors.
- `ACTOR_LOGICAL_STACKS.md` notes that "`ActorCell` still processes one actor's
  mailbox serially". Stack segments are already per invocation, so they need no
  change for concurrency.

| # | Gap | Covered by | Disposition |
|---|---|---|---|
| J-C1 | The default must become concurrent with a bounded live-invocation limit, with `serial` meaning 1. | none found | **New work, gated on J-C2 and J-C3.** Until then, keep failing closed: reject non-serial actors that need overlap, and never silently run them serially while claiming concurrency. |
| J-C2 | `critical-read` / `critical-write` sealed-state loans must guard every state access before more than one invocation can overlap. | **#1695** (receive permits, protected-state read/write loans) | Make #1695 canonical for read/write loans. |
| J-C3 | Generalise the formal model to `leases_held(a) <= leases(a)`, with serial = 1, plus loan exclusivity and a CONTROL drain. | none found | **New work** in `docs/formal/`. Keep the existing mutation test. |
| J-C4 | NIO topology "single-flight actors" | #1204 | **Check before merging:** #1204 must not hard-code single-flight as the default for ordinary actors, which would contradict ch05:39. |
| J-C5 | Replace the single `sourceReceiveSuspended` flag with per-invocation suspension slots. | none found | **New work** (part of J-C1). |

### 2.3 Heap (spec ch27)

**Done on `main`:** each actor incarnation gets an `ActorNativeArena` at spawn
(`ActorRuntime.java:9941`): u32 offsets, turn-scoped access, restart creates a
new heap, and hot-upgrade migration is in place (ch27 status table).

| # | Gap | Covered by | Disposition |
|---|---|---|---|
| J-H1 | Guest payload bytes are still JVM objects. `nodes/`, `compiler/` and `types/` never allocate into the actor arena, but ch27 rule 4 says payload "may not be" GC-managed. | **#1724** (32-bit actor slab heap, golden parity with LLVM #816) | Make #1724 canonical. Its follow-up (routing guest allocation through it) is the actual rule-4 fix. Also reconcile the older #533/#541/#548/#1272/#1273 heap lines against #1724, and mark the superseded ones. |
| J-H2 | `ActorOwnedGraphArena` and `GenerationalGraphArena` have no runtime callers. | — | Label them experimental, or wire them through #1724. Don't count them as actor-heap evidence. |
| J-H3 | Charge suspended frames to the actor (ch27 rule 8). Java is listed as "quota only". | #1666, #1488 | Covered by J-S4/J-S5. |

## 3. LLVM (`atto-source.llvm`)

| # | Gap | Covered by | Disposition |
|---|---|---|---|
| L-1 | Stack exhaustion kills the process (SIGSEGV), and compiled guest failures call `abort()`. | **#716** (per-turn tenant-VM stack quota), #715 | Land #715 → #716 as the parity path, as the spec ch25 status table recommends. |
| L-2 | `NativeActorLogicalFramesV1` is unwired. #689, #690 and #741 each propose an actor-owned frame store. | **#689, #690, #741** (+ #749, #751, #264) | **Consolidate.** Pick one canonical store; #741 (per-invocation, bounded) best matches ch25. Mark the others superseded or incorporated. Any store must key frames by *invocation*, not by physical turn, and use `max = 1` for `serial`. |
| L-3 | Arena and region misuse calls `abort()` (`ActorArena.c:35`, `ActorRegionV1.c:17`). | none found | **New work:** when the check detected and prevented the access, raise an actor-fatal trap. Keep `abort()` for broken runtime invariants. |
| L-4 | Compiled source actors use 64-bit slot handles, not u32 offsets. | #816 (slab heap, merged), #742 | Route source actors onto the slab heap. |
| L-5 | The scheduler `ActorLane.active` is a boolean, so it can't support concurrent-by-default actors. | none found | **New work**, after L-2. |

## 4. Wasm (`atto-source.wasm`)

| # | Gap | Covered by | Disposition |
|---|---|---|---|
| W-1 | `aot-host/profile.rs` sets `consume_fuel` but not `max_wasm_stack`. The spike sets 1 MiB and traps `StackOverflow`. | #222 measures the blast radius | **New work, small:** set `max_wasm_stack` per actor kind in `aot-host`. |
| W-2 | Actor-owned managed frames and suspend fencing | #188, #189, #192, #199 | Existing M1 stack. |
| W-3 | Synchronous traps contained to the owning actor | #193 | Existing. |

## 5. Shared contract drift

`atto-common-source/contracts/v1/CALLBACK_DISPATCH_AND_PREEMPTION_V1.md` (and
its legacy mirrors) still says "One actor may not execute on two carriers
simultaneously" for **all** actors, which contradicts spec ch05:39 and
ch25. A companion PR in atto-common-source aligns it.
`atto-common-source#124` already fixes the same contradiction in the core
actor-foundation contract.

## 6. Suggested order

1. **#1666** (Java recursion OOM regression).
2. LLVM **#715 → #716**.
3. Wasm W-1.
4. Consolidate the LLVM frame stores (L-2) and the Java heap lines onto #1724
   (J-H1).
5. Contract alignment (§5), then the formal model (J-C3), then #1695 loans
   (J-C2).
6. Concurrent-by-default runtime admission: J-C1/J-C5 in Java, L-5 in LLVM.
7. Per-Task segments, per-kind quotas and aggregate accounting
   (J-S2/J-S3/J-S4).

## 7. Acceptance

Use the spec's own conformance lists: ch25 §"Required conformance evidence"
items 1–10, ch18 rule 8, and ch27 §6. Every claim needs exact head SHA/tree,
real compiled `.atto` guest execution, and negative diagnostics. Zero-step CI is
**NOT EXECUTED**.

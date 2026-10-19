# Task loan + physical quiescence integration — 2026-10-19

Status: **draft runtime/compiler integration**. Guest Task execution and scoped
borrowing remain disabled.

## What this branch establishes

- Recovers the closed #1448 compiler-side `TaskAccessVerifier` onto the live
  #1407 TaskHandle/TaskScope quiescence stack.
- Reuses the canonical resolved-type `ThreadSafetyTraits` engine carried by
  the Task Send branch. READ claims require `Sync`; WRITE claims require
  `Send`.
- Loan retirement is explicitly named
  `retireAfterPhysicalQuiescence(Ticket)`; the previous public-looking
  `quiescent(Ticket)` entry point is removed.
- `TaskScope` now has a package-private runtime/compiler retirement hook.
  It runs only from `TaskHandle.getDone()`, which publishes after result
  settlement, physical scheduler exit, descendant retirement and cancellation
  graph retirement.
- A cancellation request, result Future cancellation, or result settlement
  cannot invoke the retirement hook.
- If the trusted retirement hook throws, the TaskScope child join slot remains
  live. The runtime fails closed rather than publishing scope completion while
  a loan may still be active.

## Trust boundary

The hook is package-private and is not a source-language callback surface.
This branch still does **not** authenticate a `TaskAccessVerifier.Ticket`
against a particular runtime `TaskHandle`. The eventual lowering must bind:

1. compiler-minted allocation/root/generation provenance;
2. the exact verifier ticket admitted for the child;
3. the exact runtime TaskHandle identity;
4. the TaskScope lifecycle epoch; and
5. the getDone physical-quiescence event that retires that ticket.

Until that binding exists, this is a hardened staging layer, not permission for
guest scoped Task borrows.

## Evidence required

Run exact-head JDK25 Maven/JUnit for `TaskAccessVerifierTest`,
`TaskHandleTest`, `TaskScopeTest`, and cancellation tests. Then run the
relevant GraalVM/native-image checks and LLVM/TSan parity once lowering exists.
Source-org Actions currently fail before executing job steps; do not treat those
failures as test evidence. Private cross-org testing remains gated by the
reviewed protected-main allowlist in #1430.


## Never-started admission retirement hardening — 2026-10-19

A compiler may reserve a scoped loan before invoking runtime Task admission.
If `TaskHandle.start` throws before a scheduler Task exists (for example, the
scheduler is already closed), there is no child `getDone()` event that can
release that reservation. The previous integration therefore leaked the loan
and could leave the compiler/runtime access ledger permanently live.

`TaskScope.startTask(task, retirementHook)` now has two—and only two—trusted
retirement paths:

1. normal admission: invoke the hook from the child's `getDone()` callback
   after physical quiescence and descendant retirement;
2. admission throws before a Task exists: invoke the hook immediately on the
   never-started path, because no guest execution could own the reservation.

If never-started retirement itself fails, the failure is attached as suppressed
evidence to the admission exception, the scope is poisoned/cancelled, and the
reserved join slot remains live. It fails closed rather than pretending memory
authority was released.

Current exact code/test blobs before this documentation commit:
- `TaskScope.java`: `f4c048706269a3ee7cb1c50b9ba6816269b3bb48`
- `TaskScopeTest.java`: `5b2e7a5c114fad12f2a0ed703d3df575ac917150`

Exact-head source-org fast-ci #39327703161 / job #118317693556 and
concurrency-contract #39327703029 / job #118317693612 completed with zero
recorded steps; no runtime/JUnit pass is claimed.

The alternate funded carrier has current source-free execution capacity:
re-run job #118317869431 in canary run #39303220290 completed successfully.
No private source was transferred because protected-main approval #1430 is
still absent.

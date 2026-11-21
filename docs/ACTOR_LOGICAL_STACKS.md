# Actor logical stacks on the Java backend

Status: **implemented for synchronous guest frames; executed locally** (JDK 21,
macOS arm64). Implements the containment part of language-spec chapter 25
("Receive-invocation and task-owned execution stacks") and chapter 18
("Stacks", rules 1, 5, 6, 7, 8).

## The decision

There is **no single stack per actor**. Each admitted receive invocation runs on
its own logical stack, and each source Task has its own. Ordinary actors are
concurrent by default, so several invocations of one actor can be live at once.
A shared per-actor stack would either serialize them or interleave their frames.
A `define serial actor` admits one live invocation at a time, so it needs only
one receive stack, which it may reuse once that invocation retires.

## What is implemented here

`LogicalStackQuota` charges one guest frame per nested call in the interpreter's
single call trampoline (`Evaluator.invoke`). Tail hops stay inside the trampoline
frame, so proper tail calls do not consume logical frames.

| Bound | Default | Scope | Purpose |
| --- | --- | --- | --- |
| Segment quota | 1024 frames (`ores.stack.segmentFrames`) | One receive invocation, or the implicit root on a carrier | Same overflow depth on every carrier (rule 5) |
| Carrier ceiling | `(stack - min(1 MiB, stack/4)) / 8 KiB` frames | Sum of all segments on one carrier thread | The host JVM stack never overflows first (rule 1) |
| Unwind reserve | 64 frames | A poisoned segment while it unwinds | `defer` cleanup can still call functions (rule 2) |

- `ActorRuntime` opens a fresh segment around every receive dispatch. Its quota
  does not depend on how deep the carrier already is.
- Every Oreslang-owned guest carrier declares a real stack size: control plane,
  user scheduler, JVM actor dispatchers and native pthread carriers. The default
  is 16 MiB on 64-bit JVMs and 6 MiB on 32-bit JVMs (`ores.stack.carrierBytes`,
  capped at 64 MiB like `ores.actor.carrier-stack-bytes`). The stack is reserved
  address space, not RSS. Embedder threads assume the HotSpot default stack.
- Exceeding a bound raises `LogicalStackOverflowException`. It is a stackless
  `RuntimeException`, not a `VirtualMachineError`. The actor turn loop fails the
  owning actor incarnation and keeps the carrier thread running.
- The first overflow poisons its segment. Trusted host code that swallows the
  exception still fails the incarnation, because the turn loop checks the
  segment after the dispatch returns.

## Unwind semantics

Stack exhaustion is **panic-class**, consistent with `atto-spec`
`language/defer-recover-unwind-v1.md`:

| Effect | `catch` | `trap` | `recover` | `defer` | Escalates to |
| --- | --- | --- | --- | --- | --- |
| `throw` | nearest match | may become `None` | only if still unhandled | runs | actor/task boundary |
| `raise` | bypassed | bypassed | nearest eligible | runs | parent task / supervisor |
| `panic` | bypassed | bypassed | never | runs | supervisor CONTROL |
| stack exhaustion | bypassed | bypassed | never | runs, within the 64-frame reserve | supervisor CONTROL (actor restart policy) |
| cancellation, host fatal | bypassed | bypassed | never | runs where safe | scheduler / host |

Defers run exactly once each, in reverse order, on the owning execution context.
A cleanup that exhausts the reserve fails too: it is recorded as suppressed on
the original overflow, and the remaining cleanups still run. `TrapFailurePolicy`
denies `LogicalStackOverflowException` to both `trap` and guest `catch`.

## Execution contexts and continuations

An `ExecutionContext` is the identity of a Task or a receive invocation. A
continuation is a resume point **of that same logical stack**, not a new stack.
After `await`, the Java source evaluator resumes the continuation from the
scheduler with a shallow host stack, possibly on another carrier. The suspended
frames are heap-held continuation state (`ActorContinuationLedger`); they are
not charged carrier frames. The quota bounds each synchronous stretch of a
logical stack. Continuation bytes are bounded separately.

## Calibration

Measured on HotSpot 21 (aarch64), with the segment quota lifted so that only the
carrier ceiling applies (`LogicalStackHostSafetyTest` shapes):

| Carrier stack | JIT | Every shape stops at the logical bound? |
| --- | --- | --- |
| 1 MiB, old 4 KiB/frame budget, 128 KiB reserve | default | **No**: wide expressions and mutual recursion hit `StackOverflowError` |
| 2 MiB, 4 MiB, 16 MiB, 8 KiB/frame budget | default | yes |
| 2 MiB, 4 MiB, 16 MiB, 8 KiB/frame budget | `-Xint` | yes |

Before this change, the JVM default carrier stack overflowed at about 600 guest
frames of a simple recursive call. One actor's overflow killed its dispatcher
lane and two control-plane threads, and `main` failed with
`CompletionException(StackOverflowError)`.

## Limits

- A guest frame quota bounds nested **calls**. A single function body with
  extreme expression nesting can still use more host stack per frame than the
  budget assumes. That host `StackOverflowError` stays a VM error, as the spec
  allows: a backend need not promise recovery from arbitrary host overflow.
  Parser nesting limits bound this in practice.
- 32-bit JVM sizing (6 MiB, 4 KiB/frame) is derived, not measured: no 32-bit
  JDK was available on this host.
- `ActorCell` still processes one actor's mailbox serially. The
  concurrent-by-default receive model and its per-invocation live limit are
  separate work; segments are already per invocation, so they need no change
  for it.
- Source Tasks use the carrier's implicit root segment. A dedicated per-Task
  segment would need the scheduler step boundary in `OresScheduler`.

## Parity

| Backend | Containment of one actor's stack exhaustion |
| --- | --- |
| Java (this change) | Per-invocation frame quota; actor fails, carrier and siblings continue |
| LLVM `main` | None: compiled guest failures call `abort()`. The tenant-VM chain (#1343 → #1565 → #1566) adds guarded carriers and a per-turn byte quota with actor restart |
| .NET `main` | One `ActorFrameStack` per serial actor, not per invocation. A CLR stack overflow ends the process |

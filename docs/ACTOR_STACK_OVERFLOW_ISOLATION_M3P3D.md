# M3.3d — actor stack overflow must fail only its owning actor generation

**Status:** Java source implementation and host/JVM subprocess tests authored,
not executed through the project's Maven/GraalVM/AttoLang guest CI. This is
a child of [Java #2669](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2669);
no parent branch was modified.

## Audited execution boundaries

1. `ActorCell` has an independent `ActorGuestStack` and
   `ActorFrameArena` per actor incarnation. Frame domains are
   generation-fenced. `ActorScalarCallMachine` allocates a separate
   execution ID and quota-charged actor-owned activation frame per
   supported **non-tail** call, with `TAIL_CALL` reusing stack depth.
   The arena enforces at most 64 frames / 64 KiB by default. After
   `ActorFrameArena.BudgetExceeded`, actor-owned bytes are reclaimed
   by `cancel(execution)`, while the arena stays **poisoned**. The
   mailbox-turn runtime checks `frameArena.isPoisoned()` after the
   handler, so even a host handler that *catches and swallows* the quota
   error cannot process further messages. Independent actor B retains
   a different arena, and a shared carrier can run B after A retires.
2. Legacy non-tail guest evaluation still nests JVM calls, protected by
   `GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS=64` in the
   existing sync guest path. Managed frame reservations are a separate
   budget from the JVM carrier's physical native stack. Running out of
   JVM stack before the logical guard fires is possible, particularly
   with small `-Xss` values, and can be especially dangerous with
   arbitrary host callbacks or code paths not compiler-lowered.
3. Before this change, `ActorCell.runBatchEntered` caught every
   `Throwable`, called `fail(failure)`, but then **rethrew every
   `VirtualMachineError`**, including `StackOverflowError`. That
   could kill the **shared worker** after actor A failed, undermining
   the requirement that sibling actor B survive.

## Narrow actor-turn recovery patch

Only a JVM `StackOverflowError` that has fully unwound to an
**authenticated actor turn's** `runBatchEntered` catch boundary is
translated to the new terminal, stackless
`ActorStackOverflowException`. Before failing the incarnation, the
runtime poisons A's `ActorGuestStack` and `ActorFrameArena`, sealing
further admissions even if the original error was swallowed by an
embedding layer later. `terminateTree` then uses the existing actor
STOP protocol: seal mailboxes, cancel owned asynchronous work and
structured descendants, wait for real physical quiescence, and retire
A's exact arena; peer/sibling actors not owned by A do not share its
poisoned budgets. The recovered native `StackOverflowError` is **not
rethrown** to the shared carrier.

**Non-stack VM fatal errors** (e.g. `OutOfMemoryError`,
`InternalError`, `LinkageError` and `ThreadDeath`) preserve their
existing fatal propagation policy. The outer `runBatch` executor
boundary also preserves its fatal propagation behavior, so a broken
runtime/TurnExecutor is **not** silently reclassified as a recoverable
actor guest exception.

## Carrier-local guest invocation lease audit

The old `GuestCallDepthGuard` holds its active synchronous call `Scope`
in a JVM carrier `ThreadLocal`. Normal lexical `leave()` removes it,
but a broken native unwind or host interpreter frame can bypass the
balanced `leave()` protocol. Without physical-turn cleanup, actor B
might inherit A's stale `Scope` when the SHARED worker is reused:
`GuestCallDepthGuard.enter()` previously treated that mismatch by
poisoning **both** the stale and the new actor, violating sibling
isolation.

`GuestCallDepthGuard.quarantineLeakedActorTurnScope` now executes
**after each mailbox envelope/continuation**, before any additional
message in the same physical batch. An independent second fence runs
in the actor's physical-turn `finally`, before `endTurn()` releases
the carrier. It first removes the `ThreadLocal` so that no later sibling
can observe stale A authority, then poisons **only A's** original
`ActorGuestStack` and `ActorFrameArena`, releases each outstanding
logical invocation charge in descending depth order, and cancels A's
frame bytes using the **original ExecutionContext**, even if logical
unwind detects corruption. A stale scope causes actor A to fail-stop.
The caller's `finally` still executes `endTurn()` and removes actor
identity, even if malformed cleanup fails. A different actor's arena
is never used as the reclamation destination.

This is **best-effort JVM actor-turn containment**, not a guarantee of
safe recovery after arbitrary native or JNI stack exhaustion, fatal
OS signals, memory corruption, or resource exhaustion that prevents
cleanup itself. Those need true process/isolate separation (especially
untrusted actors), and a supervisor must treat an unrecoverable worker
as quarantined. A structured child of the failing actor is cancelled
by existing ownership policy; that is different from an independent
sibling actor. Never market this as native physical per-actor stacks.

## New tests (AUTHORED_NOT_EXECUTED)

`ActorStackOverflowIsolationTest` adds **10** host-runtime JUnit methods:

- Synthetic JVM `StackOverflowError` fails only A as
  `ActorStackOverflowException`; A's arena is poisoned/retired and
  zeroized. Healthy B executes on the exact same SHARED worker.
- Actual non-tail recursive JVM stack overflow inside A also unwinds
  to the actor failure boundary; B runs afterward on the same worker.
- Managed non-tail frame quota overflow, intentionally swallowed by a
  host actor handler, still terminates A and does not process its next
  mailbox item; B handles two messages with an unpoisoned arena.
- Uncaught frame-budget failure retires A with zero arena bytes.
- A new independent actor successfully runs bounded scalar recursion
  after another actor exhausted its own quota.
- An ordinary `IllegalArgumentException` guest failure is not
  misclassified as a native stack overflow.
- An unfinished `GuestCallDepthGuard` lease is **forcibly revoked and
  its original actor's logical depth/managed byte charges reclaimed**
  at physical turn exit, without poisoning a sibling on the same carrier.
- `StackOverflowError` concurrent with a deliberately leaked guest
  guard scope does not preserve the old `ThreadLocal` across actor
  turns; B can still enter an independent depth-one guarded call.
- A deterministic two-mail same-batch test uses a bounded admission
  barrier to prove that actor A cannot process **its own second mail**
  after the first mail leaks a guest-call lease; the per-envelope fence
  fails A before continuing its batch.
- Forked `-Xss256k` JVM process runs
  `ActorStackOverflowLowStackProbe`: real native recursion causes A
  to fail, B executes on the same surviving SHARED carrier and prints
  the acceptance receipt
  `ACTOR_NATIVE_STACK_OVERFLOW=A_FAILS_B_SURVIVES`.

The subprocess probe is authored but **NOT EXECUTED**; it must run on
JDK 25 with the project's actual Maven/GraalVM classpath and a bounded
deadline. Its purpose is to distinguish an actual shallow native
stack from synthetic `StackOverflowError`, not to certify JNI
fault containment.

Expected exact-head test command (when runner admission is restored):

```sh
mvn -B -ntp -Dtest=ActorStackOverflowIsolationTest,ActorScalarCallMachineTest,ActorScalarBytecodeVerifierTest,ActorGuestStackTest,GuestCallDepthActorHostModelTest,ActorScalarIterativeLanguageTest test
```

All preexisting negative compiler/cancellation/sibling tests still
require execution. GitHub Actions private `runner_id=null` and
`steps=null` mean runner admission failure, **never proof of stack
isolation**. Record exact head SHA, tree, runner and attempts, JUnit
XML, subprocess stdout/exit code, actual `-Xss256k` invocation,
actor A cause, actor B success and same-carrier identity.

## Remaining hard requirements

Full AttoLang guarantee requires **universal** nonrecursive compiler
lowering: classes, ordinary function/method calls, object roots, typed
exceptions and `defer`, suspension and resumption, actor-owned
continuations, and correct cancellation/owner generation checks.
The unsupported legacy evaluator still uses JVM recursion. A full
supervisor restart test with generation-fenced old handles, an
isolated-worker killability test, and independent LLVM/.NET/Wasm/Forge
guest parity are NOT_EXECUTED. Do not merge without independent
`ORESoftware` review, real stepful CI and security gates.

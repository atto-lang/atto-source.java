# Actor frame integration on current main

## Execution-scope hardening follow-up

`ScalarFrameStorage` now validates its exact logical scheduler/invocation context
and authenticated physical actor segment before frame access, allocation, or
cleanup. The previous implementation kept a context value but did not compare
it against the current caller; an adversarial carrier could read a live actor's
scalar local. That regression failed before the guard and passes after it.
Foreign read/write/growth/close attempts are rejected before mutating or
poisoning the owner. A second source-task regression rejects a completed
execution's retained storage while preserving the new task's own stack.

The combined current matrix passes **110 Java tests**, with no failures, errors
or skips: 71 frame tests and 39 boundary tests. These are separate from Spin's
110 HTTP checks. Earlier counts below describe the initial integration.

Logical stacks belong to execution contexts. This scalar interpreter is
synchronous, so its access lease is restricted to its authenticated physical
segment. That is not a permanent thread-affinity rule for logical stacks:
supported source-task suspension preserves the execution context and obtains
a new authenticated turn on resume. General scalar continuation spill/rebind
remains unimplemented and must not reuse a retained synchronous frame object.

This branch reconciles the actor scalar-frame stack through PR #2640
(`2febca5b8abec8897906c42a4e4733320616bf5a`) with current main
(`6d8eb70048eafc9a84c5aebe9c1bb06104f16e76`). Both histories remain intact.
The three textual conflicts in `ActorRuntime` retain both sets of behavior:
bounded asynchronous send admission and unique frame domains, WAL actor proof
minting and frame accessors, and storage actor ordinals and per-actor frame state.
No source privacy, factory capture, physical turn, or WAL admission rule is
removed to make the older frame tests pass.

## Executed integration and fixes

The focused Maven matrix executes **69 tests**, with zero failures, errors or
skips. It includes actual `.atto` parser/typechecker/evaluator execution as well
as distinct host/runtime frame, source-task, suspension, cleanup and quota tests.
Tests ran locally on macOS arm64 with Temurin JDK 25 and Maven; this is JVM
execution, not Graal native-image or independent collector evidence.

An additional **39 tests** cover physical actor turns, mailbox execution,
two-phase source-scheduler close, asynchronous finalization, WAL actor proofs,
and Task capture admission. All pass with no skips. The seven compiler-backed
guest tests also pass with Surefire `-DargLine=-Xss512k`; that constrained JVM
stack check does not prove containment of arbitrary Java, JNI or FFI frames.

- Actor guest fixtures now explicitly import their pure helpers from
  `actor_frame_helpers.atto`. They run through `LinkedProgramRunner`, which
  links the whole source graph before initialization. Direct single-source
  `Context.eval` does not load that graph. File-level implicit actor captures
  remain rejected.
- Host/runtime observation tests use the existing trusted factory API for their
  captured test counters and leases. Ordinary guest/host factory restrictions
  remain unchanged; these observations do not count as compiled guest evidence.
- The failing-actor fixture waits for both actors' physical completion before
  the runner closes. It verifies the failed actor's expected terminal frame
  budget outcome and the independent sibling's `starting|alive` output.
- The iterative scalar path now raises `GuestCallDepthExceededException` at its
  bounded non-actor 2048-frame limit. The former ordinary `IllegalStateException`
  did not preserve the terminal budget classification. Tests verify controlled
  failure and subsequent fresh execution.
- Frame layout admission uses subtraction after bounded operand checks, so
  `int` overflow cannot wrap an oversized local/operand count past the 256-slot
  limit. A regression checks huge counts, unchanged live-frame accounting,
  and a valid exact-boundary layout.

## Limits and next implementation work

Every actor in this branch owns its logical frame arena, but its frame payload
is still JVM-managed byte storage and the general source-object heap remains
unfinished. This does not establish independent native heaps for all guest
objects or full stack containment. The admitted pure synchronous scalar graph
is iterative; arbitrary actor bodies, methods, closures, FFI/native recursion,
and complete typed continuation restoration are not.

Source actor scheduling here still uses one physical execution at a time. It
does not yet meet concurrent-default actors with independent invocation frames
and sealed-state `critical-read`/`critical-write` access. Checked ref32 arena
coverage, complete allocation/borrow/drop inventories, physical retirement and
equivalent LLVM/.NET compiled guest evidence remain requirements. Tenant-VM
work follows proof of independent actor heaps/arenas and stacks.

LLVM PR #1649 provides separately executed restricted native scalar-frame
evidence; it does not certify this JVM implementation or the complete Spin
server. Hosted CI, Native Image and full backend parity remain unverified.

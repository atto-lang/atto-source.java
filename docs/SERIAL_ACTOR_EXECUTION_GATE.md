# Serial actor lifetime admission and inferred critical state

Status: **draft runtime integration; source message execution supported,
source HTTP attachment not yet implemented**.

`serial` is an actor scheduling modifier, not the visibility/isolation kind.
A serial actor can have one active **handler lifetime** at once: one HTTP
request OR one actor message, never one of each. Builtin actor inheritance
must preserve this invariant; guest code cannot override core scheduling,
mailbox, cancellation, or sealed `self.handle_state`.

## Syntax

```oreslang
define serial actor Counter as
    state { count: int = 0; }
    pub increment(): int using state.read, state.write {
        return self.handle_state(|s| -> {
            s.count = s.count + 1;
            return s.count;
        });
    }
end
```

The compiler infers the **effective critical** property for inline state
callbacks on serial actors. Explicit `critical |s| -> ...` remains valid.
Non-serial actor callbacks still require explicit `critical`. Neither
spelling weakens declared effect upper bounds, read/write distinction,
owned loan non-escape, no live-loan suspension, or actor-turn identity.

## Lifetime and carrier scheduling

A serial actor is IDLE, RUNNING or SUSPENDED (plus terminal stop). Await
releases the native worker/carrier but **does not** free the actor-wide
lifetime admission reservation. The same handler continuation must be
readmitted to a physical carrier before it can resume, subject to
generation/owner checks. The next `receive()` is not called while the actor
is RUNNING or SUSPENDED. A TCP keepalive connection may persist after
request-handler completion and does not itself keep the actor busy.

`SerialActorAdmission` provides bounded FIFO admission for synthetic
HTTP and message lifetimes, non-blocking future completion, cancellation
removal, stale lease fencing, and actor shutdown. It never runs guest
callbacks while holding the internal bookkeeping monitor. The adapter
must call `lease.requireActive()` before guest handler invocation and
continuation resume; `lease.close()` is required on all handler exits,
including panic/cancellation, to admit the next waiting request/message.

## Completed narrow gates

- Parser, state/effect/type-checker and evaluator serial inference exist
  on the Java critical-state branch.
- Source `SerialActorImplicitCriticalTest` executes a 3-message scalar
  Counter through Graal's polyglot guest evaluator.
- `SerialActorAdmissionTest` checks mixed 1,000-handler synthetic traffic,
  FIFO reservation, suspension, cancellation, shutdown and stale leases.
- The funded cross-org exact-source JDK25 mirror exercises the production
  admission class with the same 1,000 mixed synthetic handlers.

## Still release-blocking

- Wire **both** source HTTP transport and actor mail dispatch to the same
  per-actor trusted admission instance; do not accidentally admit one
  request and one message concurrently.
- Bind leases to supervisor/actor incarnation and use the scheduler's
  generation-fenced continuation ticket, never guest-mintable booleans.
- Prove exactly-once release on handler success, cancellation, panic,
  actor end and supervisor restart; reject stale wakers after reincarnation.
- Add real HTTP loopback + concurrently queued messages with true await
  and cancellation, not only synthetic gate operations.
- Full GraalVM Maven/JUnit, native-image and whole-tree exact-source CI.
  LLVM guest source lowering is still unsupported even though native actor
  state and critical policy prototypes have targeted CI.

Do not report HTTP-capable serial actors as complete until these gates run.

## Audit hardening: re-entrancy, runnable state and shutdown

The bounded Java gate now uses an **iterative non-reentrant drainer**:
`CompletableFuture.complete` can run dependent callbacks synchronously, and
those callbacks can close their lease immediately. The drainer must never
recurse through 4,096 queued callbacks or leave an active ticket stranded
when pending cancellation races promotion. The runtime test and funded JDK
smoke cover 4,096 immediate completion callbacks, ordered cancellation,
and cancellation-versus-promotion races.

`requireActive()` now demands a **RUNNING** lease. A SUSPENDED lease still
owns actor-wide admission but cannot authorize guest execution. The same
separation appears in native LLVM as `admitted(ticket)` (logical ownership)
versus `may_execute(ticket)` (runnable now).

**Critical release condition:** an admission future is a scheduler signal,
not permission for its completion thread to run guest code. In particular,
Java `CompletableFuture` dependent callbacks may run inline on a transport
completion thread. Production adapters must schedule guest execution through
the trusted actor scheduler with a current execution-context and generation
ticket, then check runnable ownership immediately before entry; never invoke
the guest from a bare completion callback. The gate is not a replacement for
the actor scheduler.

Neither gate can forcibly unwind a handler that is *already executing* when
supervisor stop occurs. Revocation prevents later admission/re-entry; the
scheduler must cancel/interrupt cooperatively, await all existing physical
turns and state loans becoming quiescent, then retire actor-owned state.
Likewise `lease.close()` must be called only after handler cleanup/unwind,
not before `defer`, `finally`, `recover`, critical guards or response
finalization complete. Double completion and stale continuation tickets
remain forbidden. No HTTP/message production wiring or full native-image
verification is claimed by these targeted admission tests.

## Runtime integration milestone (2026-10-11)

The linked Java/Graal source evaluator now reports serial declarations through
the trusted `ActorCodeExecutor.isSerialActor(type)` contract, derived from the
immutable class AST. A persistent source-actor `ActorCell` creates its own
`SerialActorAdmission` instance; user-mail handling acquires one MESSAGE
lease and keeps it logically reserved while the source receive suspends.
Cooperative continuation segments resume under that same lifetime and
re-suspend until the originating source task completes. The lease is only
released **after** the actor envelope checks that native scratch scopes
have closed. Actor finalization revokes the gate after active carrier turns
quiesce, before reclaiming its state.

**Important limitation:** the runtime now issues per-task
`SerialExecutionTicket` identities for source tasks; full end-to-end
execution-context authority still requires verifying all detached child tasks,
foreign completion paths, supervised restarts, and guest unwinding on the
full compiled GraalVM runtime, not just isolated ticket tests.

**HTTP is deliberately fail-closed.** `NativeHttp.Handler.dispatch` currently
spawns one actor per request. Registering a serial source actor via
`http.handler("Counter")` now rejects registration instead of silently
creating different actors with different states. Proper serial HTTP needs
one persistent actor identity per route/instance, routing each request and
mailbox message through the same gate, with independent per-request timeout,
response/cancellation and supervisor restart handling.

Exact same-source fixtures
`tests/fixtures/serial-counter-cooperate.ores` and
`tests/fixtures/serial-http-must-not-spawn-per-request.ores` are mirrored
in the funded test org. A focused JDK25 parser + state/effect verifier run
proves these fixture admission rules; it is **not** a full GraalVM guest
execution test or actual HTTP transport verification. Guest JUnit tests in
`SerialActorImplicitCriticalTest` still require allocated Maven/JVM CI.

## Audit: immutable authority and future-callback provenance (2026-10-11)

**Authority from code images.** The runtime now resolves `serial` from
the VM-published immutable `CodeImage.program()` using
`SourceActorSchedulingPolicy`. At source actor spawn, any disagreement with
`ActorCodeExecutor.isSerialActor` fails closed before creating the cell.
`NativeHttp.handler` also validates this agreement *before* deciding whether
the per-request adapter is safe. A guest-facing or host-adapter boolean
alone never grants or removes serial admission. Unknown, duplicate and
non-actor declarations are rejected.

**Fencing captured future callbacks.** `ContinuationTarget` captures a
`SerialContinuationAuthority` containing the originating
`SerialActorAdmission.Lease`, rather than just actor ID. It refuses
already-retired or unowned-while-busy callbacks before admission to the
CONTROL queue, and checks runnable authority again on the actor carrier.
A callback from one completed receive cannot borrow the following receive's
lease even if both share the same persistent actor. The authority constructor
also checks the issuing gate identity, so identical numeric generations
from *different actors* never authenticate each other.

**Scheduler provenance now partially connected.** Source tasks started
through `ActorRuntime.startActorTask` now capture a runtime-minted
`SerialExecutionTicket` holding actor incarnation, task ID, and the
exact active serial receive lease. The actor continuation envelope carries
that ticket and checks it on physical re-entry. TaskRunner's enum-driven
state transitions preserve its placement across `await` and
`rt cooperate`. These targeted ownership checks do **not** prove all
unrelated scheduler callbacks or detached child-task lifetimes; complete
structured-child cancellation and full GraalVM guest tests remain
release-blocking. The criterion remains the *same* original receive,
not merely an actor-wide busy boolean.

The funded JDK25 test mirror compiles the exact
`SourceActorSchedulingPolicy`, `SerialActorAdmission`, and
`SerialContinuationAuthority` implementations and tests spoofed metadata,
foreign leases, stale prior-handler callbacks, and unowned callbacks.
These targeted runtime-substrate checks do not compile the 300KB
`ActorRuntime`, `NativeHttp`, or GraalVM guest JUnit tests and therefore
do not establish end-to-end runtime correctness.

Both LLVM and GraalVM still need source-level HTTP/mailbox cross-ingress
tests and full build/test verification before draft PRs may merge.

## ActorCell audit hardening (2026-10-11)

The serial source actor now stages a finished receive lease inside the active
envelope but does **not** call `close()` until the `MessageEnvelope`
try-with-resources scope has finished. This orders native scratch checks,
private-behavior validation and message-envelope release callbacks **before**
any subsequent actor admission. If a release or earlier check throws, normal
fail-stop shutdown fences the still-active admission; it must not be
prematurely promoted.

`SerialContinuationAuthority` captures the exact issuing admission instance
and source receive lease. Constructing authority with a lease issued by a
*different* actor gate is rejected even when both actors' numeric lease
generations coincide. Unbound actor-scoped callbacks are allowed only while
their gate is both **open and idle**, checked together. A callback cannot
queue or enter after stop, and an older handler's callback cannot execute
using a later handler's lease. The originating bound receive may still queue
while suspended, but cannot execute until its own lease has resumed.

### Executed evidence and limitations

[Funded exact-source targeted CI 38178159875](https://github.com/akrion-sim/akrion-sim-e2e/actions/runs/38178159875)
passed: JDK25 compiled real `SerialActorAdmission` and
`SerialContinuationAuthority` with cross-actor, equal-generation, suspended,
stale and post-stop negative tests. The exact current `ActorRuntime.java`
source was syntax-parsed by the JDK compiler and its resource-release ordering
checked against the actual source structure. Previous JVM admission stress
(1,000 mixed, 4,096 inline, 300 cancellation races) and native LLVM
GCC/Clang sanitizer tests ran on the same mirror. This is **not** full Maven
type linking, guest JUnit execution, persistent HTTP ingress, LLVM guest
source lowering or AOT release validation. The mirror's unrelated
`secrets-audit` workflow still fails its `ores-sops` environment check.

Production transport must continue rejecting serial actors until a persistent
actor per route shares admission between HTTP requests and messages. An
isolated synthetic gate or AST parser pass is not proof of transport safety.

## Runtime-issued per-task continuation tickets (2026-10-11)

`ActorCell.issueSourceTaskTicket()` now issues a monotonic task identifier
within one actor incarnation and captures the exact actor ID, opaque incarnation
identity, and originating serial handler lease. The ticket is **host-only** and
cannot be promoted or used by guest code as a scheduling permission.

`OresScheduler.startActorBound(task, taskPlacement)` pins one trusted
per-task placement for the entire generated async state machine: initial
dispatch, each `await` continuation, and each `rt cooperate` segment.
Physical carriers remain managed by the existing actor scheduler. The
execution context and compiler-lowered task retain the actual locals,
cleanup stack and resume position; the ticket carries permission and identity,
not a second copy of guest state.

The actor's bounded CONTROL mailbox now carries an optional
`SerialExecutionTicket`. Before any serial handler transitions from SUSPENDED
to RUNNING, the actor runtime validates the ticket against the current
actor/incarnation and the exact active lease; it rechecks runnable permission
immediately before running the queued source task. Future callbacks captured
inside source actors and the source receive's terminal completion also carry
tickets. An anonymous/unattributed continuation cannot resume a busy serial
handler. The serial actor scheduler's old anonymous fallback is rejected.

Ticket numbers and handler generations alone are **not** security identities:
different actors can share the same numbers. The opaque actor incarnation and
the issuing admission lease must both match. Actor shutdown and supervisor
replacement fence all prior tickets.

### Executed focused verification

[Funded CI #38179876034](https://github.com/akrion-sim/akrion-sim-e2e/actions/runs/38179876034)
passed the exact-source Java 25 compilation and **execution** of upstream
`OresScheduler`, `OresFuture`, `Awaitable`,
`MixedInteropBridge`, `SerialActorAdmission` and
`SerialExecutionTicket` using a minimal test-only `ActorRuntime` stub.
The test exercises actual source scheduler await/cooperate dispatch and stale,
foreign-incarnation, canceled and post-restart ticket rejection. The real
`ActorRuntime.java` source was separately JDK syntax-parsed with static
integration assertions. The previous Java admission stress and LLVM native
sanitizer checks also passed on that run.

**Not yet proven:** type-linked full `ActorRuntime` and Graal evaluator Maven
build, source guest JUnit `SourceSerialActorCooperateAdmissionTest`,
GraalVM native-image, persistent serial HTTP request ingress, and native LLVM
source-level continuation ticket parity. The mirror's unrelated
`secrets-audit` environment job still fails due to missing `ores-sops`.
Keep both backend PRs in draft. Do not confuse focused upstream-source
scheduler execution with full Oreslang guest continuation execution.

## Enum lifecycle and bounded formal model — Oct 11, 2026

- `SerialActorAdmission.Lifecycle` is an enum with `IDLE`,
  `RUNNING`, `SUSPENDED`, and `STOPPED`; it is **derived under the
  gate's existing monitor**, so there is no second mutable state that can
  disagree with the actual active lease. `STOPPED` fences future admission.
- `OresScheduler.TaskRunner.Phase` uses a typed enum rather than integer
  constants, with NEW → QUEUED → RUNNING → WAITING → QUEUED transitions
  and terminal cancellation from every nonterminal state. This preserves
  the previous atomic compare-and-set scheduling implementation.
- `verification/serial_actor/SerialActorLifecycle.tla` and its TLC
  config model bounded serial handler submission, FIFO admission, ownership,
  task-ticket capture, suspension/resumption, stop/restart, and cancellation.
  TLC checks **TypeOK**, **SingleHandlerLifetime**, **FifoAndOwnership**,
  **NoForgedExecution** and **NoDuplicateTaskExecution**.
- Funded TLC run [38180159775](https://github.com/akrion-sim/akrion-sim-e2e/actions/runs/38180159775)
  explored **19,616 distinct states**, depth 26, zero invariant
  violations, using verified stable TLC 1.7.4. The same runner executed
  5,000 enum-lifecycle transitions and checked the actual Java scheduler
  source AST syntax; LLVM native focused tests passed.

**Verification is bounded, not an unbounded theorem:** two modeled
handlers, two task identities, three admission generations, one restart.
`TaskRunner` type-linking and full GraalVM guest execution were **not**
part of these focused tests. The full source HTTP adapter still rejects
serial handler registration until one persistent actor per route shares
HTTP and mailbox admission. Additional proof obligations include liveness
under fair scheduling, nested async child ownership, physical quiescence
during cleanup, and the complete Java/LLVM runtime refinements.

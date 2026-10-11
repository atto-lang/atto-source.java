# Executable source actor `tear_down()` — first Java/GraalVM slice

**Status:** runtime and source lowering implemented on the feature branch; the
exact-head Maven/JUnit/GraalVM build and concurrency audit have **not passed**.
This is deliberately narrower than the normative cross-backend
`ACTOR_TEARDOWN_FAILURE_HOOK_CONTRACT.md`.

## Source API

```ores
define actor Worker as
    @Implements
    tear_down(): void {
        // Synchronous actor-local cleanup. An async teardown continuation is
        // not available yet.
    }
    receive(ActorMail<String> mail): void {
        self.end();
    }
end
```

The parser recognizes a single, nonstatic, nongeneric, zero-argument,
synchronous `void` `tear_down`. Actor-local `on_panic` remains permanently
forbidden; `on_throw` and `on_raise` remain fail-closed until typed dispatch
is implemented. Ordinary calls such as `self.tear_down()` are rejected by
the evaluator; only the runtime's lifecycle lane invokes the hook.

## Lifecycle ordering and bounds

- For an initialized source actor on cooperative self-stop or external
  `ActorRef.stop()`: close/clear pending mailbox registrations, preserve the
  current admitted turn, execute `tear_down` at most once on the actor lane,
  execute a registered `endWithCleanup` final synchronous callback, then
  retire actor state and settle `done` after the physical turn exits.
- An external stop while no actor turn is active schedules a private teardown
  turn; user mail remains sealed. `tear_down` cannot inherit the last
  `ActorInboxMail` envelope.
- A teardown exception is recorded as the terminal cause and physical
  finalization continues. The implementation records secondary final-cleanup
  failures as suppressed evidence rather than resurrecting the actor.
- Startup failure, unhandled failure, cancellation, runtime-wide close,
  force-kill, and suspended source-receive retirement **do not execute guest
  teardown** in this slice; trusted runtime finalization remains responsible.
- `tear_down` with direct `await`, `suspend` or `rt cooperate` is
  rejected at parser admission, before shutdown can execute guest code.
  Suspension through indirect calls remains runtime-checked by the evaluator
  until compiler-wide effect admission is implemented; it must not be
  advertised as statically proven nonblocking. There is no claim that arbitrary
  sync user operations can never block: compiler-enforced I/O effect
  restrictions and nonblocking async teardown remain follow-ups.
- For Java heap objects, dropping actor roots only makes them eligible for GC.
  This does not provide deterministic physical JVM heap reclamation. Owned
  native arenas and actor memory accounting must still respect outstanding
  pinned loans and FFI callbacks.

## Bounded async host completion gate (not source lowering)

`ActorAsyncTeardownGate` is a separately tested **host-only primitive**.
It observes a `CompletionStage`, installs a trusted scheduler timeout, and
atomically records SUCCESS/FAILURE/TIMED_OUT. Producer and timer callbacks
may **only** ask the CONTROL scheduler to enqueue a turn; they do not invoke
Oreslang guest code. A delayed operation cannot overwrite a timeout result,
and constructor failure does not silently leave a Future with no deadline.

**Not yet connected:** `ActorRuntime`'s stopped-actor CONTROL admissions,
the `OresEvalRootNode` source method continuation scheduler, and the
native/FFI resource registry. In particular, a timeout only terminates
logical waiting and is **not** evidence that a live native loan, file descriptor
or raw callback can be freed. Source `async tear_down` remains fail-closed.
The next change must reserve a lifecycle continuation slot after user mail
is sealed, reenter the actor's exclusive lane on completion, run the
last guest cleanup callback exactly once, fence remaining resources, and
only then settle `done`.

The exact Java host-gate source has been compiled with
`javac --release 21 -Xlint:all -Werror` and independently exercised against
success, failure, deadline, duplicate/late completion, rejected CONTROL
notifications, and a timer which executes synchronously before `schedule()`
returns its handle. All focused local smoke tests passed. The terminal
notification now drops references to the original CONTROL closure after
completion/timeout, preventing a never-resolving producer Future from retaining
that closure indefinitely. A rejected CONTROL wake is observable via
`notificationFailure()` and **must** be escalated or polled by the trusted
supervisor once integrated.

The actual source-level `async tear_down` is still unavailable. In particular,
none of these focused tests prove actor-affine continuation scheduling, native
resource quiescence or `actor.done` finalization. Full project Maven/JUnit
remains outstanding.

## Required before merge

1. Run `mvn -Dtest=ActorSourceTearDownTest,ActorSupervisorPseudoHandlerAdmissionTest,SourceActorEndCleanupExecutionTest,ActorEndCleanupTest test`
   and then full Maven/GraalVM conformance on **this exact branch commit**.
2. Test races among external `stop`, `cancel`, pending continuations, runtime
   `close`, detached worker groups, and teardown failure.
3. Implement bounded, nonblocking `async tear_down` via actor-affine pinned
   continuations, cancellation deadlines and reactor-driven resource closing.
4. Integrate a trusted per-actor resource registry (socket/FD/FFI callbacks)
   and prove no free before quiescence.
5. Implement the same source semantics and physical heap retirement in LLVM,
   then run identical differential fixtures.

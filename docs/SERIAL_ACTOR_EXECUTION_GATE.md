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

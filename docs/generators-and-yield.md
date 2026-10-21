# Generators and `yield`

Oreslang generators are synchronous, resumable iterator frames. They are
deliberately separate from actor scheduling and from asynchronous futures.

## Source form

`yield` is an expression, following the useful part of the JavaScript
generator protocol:

```ores
fnc exchange(int first) => Generator<int, String, int> {
  val int reply = yield first;
  yield reply + 1;
  return "done";
}
```

The generic parameters are:

```text
Generator<Yield, Return, Next>
          Y      R      N
```

- `Y` is the type produced by each `yield expression`.
- `R` is the type produced by the generator's final `return`.
- `N` is the type sent back into the suspended `yield` by `next(value)`.

For ordinary iteration, the shorthand is:

```ores
fnc numbers() => Generator<int> {
  yield 1;
  yield 2;
  return;
}
```

`Generator<T>` is equivalent to `Generator<T, void, void>`.

A generator can be consumed by `for ... of` using its yielded type:

```ores
fnc sum(Generator<int> values) => int {
  let int total = 0;
  for (val item of values) {
    total = total + item;
  }
  return total;
}
```

## Runtime protocol

The lowering target is `OresGenerator<Y,R,N>`. A compiler-generated heap
frame owns the program counter, live locals, loop state, and source cleanup
state.

A frame resumes with one of:

- `START` for the first `next()`;
- `NEXT(value)` for a normal resume;
- `THROW(error)` to raise an error at the suspended yield point.

It returns either:

- `Yielded(value)`; or
- `Complete(returnValue)`.

No host thread is parked while a generator is suspended.

The first `next(value)` is treated as START because no source-level yield is
suspended yet to receive that value.

## Cleanup

Generators are `AutoCloseable`. Abandoning a suspended generator closes its
frame exactly once so compiler-lowered `finally`/defer cleanup can unwind
deterministically.

A frame failure is fail-closed: the generator records the failure, closes its
frame, and rejects later resumes.

Reentrant resume is rejected.

## Ownership

A `yield` is an escape and suspension boundary.

- move-only yielded values are moved to the consumer;
- a local borrow cannot be yielded because the consumer can outlive the borrow;
- actor-confined borrows cannot escape through a generator;
- `MutexGuard` values cannot cross a yield;
- a generator frame itself is local/non-sendable and cannot be returned across
  an actor callable boundary.

The value supplied by `next(value)` is typed as `N` when execution resumes.

## Relationship to actors

Generator `yield` is **not** an actor scheduler primitive.

If a synchronous generator is consumed while an actor owns its execution lease,
the generator runs synchronously in that actor turn until its next generator
yield or completion. Source code never has to add `yield` merely to keep actor
scheduling fair.

Actor fairness uses compiler/runtime scheduler safepoints and reduction budgets.
Actor `await` uses `ActorContext.suspendOn` and makes the actor runnable again
when its future completes.

These mechanisms may share compiler infrastructure for heap/state-machine
frames, but their ownership and wakeup semantics remain separate.

## Async generators

`async fnc ... => Generator<...>` is intentionally rejected. Async generators
need a distinct `AsyncGenerator<...>` contract with backpressure, cancellation,
and asynchronous `next()` semantics; it should not emerge accidentally from
mixing synchronous generator yield with `Future`/actor await.

## Current lowering boundary

The parser, static type/ownership passes, and `OresGenerator` runtime protocol
are present. The recursive JVM reference evaluator deliberately refuses to
execute a source generator body until compiler generator-frame lowering is
active; it must never emulate laziness by rerunning a function body and
duplicating side effects.

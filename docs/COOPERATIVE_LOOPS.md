# Cooperative loops and collection operators

Oreslang uses **one VM-owned source-loop continuation engine** for both:

1. automatic preemption of CPU-only loops inside heap-owned `SourceTask` executions, including synchronous actor `receive(ActorMail<T>)` loops; and
2. explicit `rt cooperative for ...` loops used for throughput-oriented work.

An explicit `rt cooperative` hint does not create a second scheduler, a
per-iteration `Future`, or a second actor state lease. Only the existing
`OresScheduler.cooperate()` operation ends the current physical carrier
dispatch and requeues a heap-owned continuation after the carrier unwinds.

## Syntax and budgets

```ores
rt cooperative for let i = 0; i < count; i++ {
  process(items.get(i));
}
```

Normal `for`, `loop`, `for-of` and destructuring `for-of` loops inside
continuation-backed source tasks receive **automatic** soft cooperation without
any new source syntax. For normal loops, cancellation is polled every 32
completed backedges; the target quantum is 2 ms or 512 backedges.

Explicit cooperative `for` uses a tighter target: 1 ms or 128 backedges,
sampled every 16 completed cooperative backedges. Both targets are soft:
a lengthy single callback, nested synchronous helper, GC pause, host/FFI
work or a live mutex guard can extend execution. Loops never replay completed
side effects when resuming; `break`, `continue`, condition checking and
updates follow ordinary Oreslang semantics.

UNTRUSTED actor loops still charge fuel on *every* backedge. They cannot
explicitly request cooperation, and automatic quantum handoff is disabled,
until logical-task fuel/wall/CPU quotas are verified across every continuation
and carrier migration (#402 / #461). Guarded critical sections are no-yield
regions. Actor mailbox execution remains single-writer.

The metric `process.descriptor.source_loop_cooperates` reports completed
cooperation decisions, not per-element or per-loop checks.

## Collection operators

`stdlib/cooperative.ores` exposes ordered sequential algorithms:

- `map<T,R>`, `filter<T>`, `reduce<T,R>`, `forEach<T>`
- `scan<T,R>`, `flatMap<T,R>`, `any<T>`, `all<T>`

**Ownership contract:** the current `Array<T>` input parameter is **owned and
consumed** by every cooperative collection operator. The caller must not
reuse its original binding after the call; obtain any needed length/metadata
before passing it. A plain `borrow Array<T>` cannot be carried across a
cooperative handoff. Non-consuming variants would need an explicit
shareable-readonly handle or an owned snapshot, with the associated lifetime,
aliasing and heap-budget rules. Do not silently copy or alias mutable elements.

All iterate with the *same* source `rt cooperative for` backedges. Nested
`flatMap` loops share the enclosing source task's bounded turn, rather than
spawning extra Futures or worker actors for each nested element. They do not
automatically run in parallel on GPU/CPU cores. Input length is snapshotted
on operation entry. Each callback executes once, in input order, and
short-circuit operations stop after their decisive result.

These library functions use Oreslang's suspendable synchronous source-call
lowering: from an already continuation-backed source task, an invoked helper
that cooperates is resumed by the caller's source continuation, preserving
its declared result type. The suspension does not authorize a blocking JVM
wait on an actor carrier. This contract must be tested across imported
modules and callbacks, not assumed from a plain synchronous invocation.

No automatic effect rollback is promised after a callback throws. Do not
move mutable actor-owned values into an independent task or across actors
without checking ownership/Sendability and borrow lifetimes.

## Realtime limits and follow-ups

This is **soft realtime fairness**, not hard realtime deadline enforcement.
A hard bound requires schedulable, worst-case execution-time-verified
code, bounded no-yield critical sections, isolation of native/FFI blocking
work, suitable OS/kernel scheduling and GC/JIT/allocation bounds.

Next generalization targets: synchronous unary actor `run`, stream/observable
operators with backpressure, native numeric/vector intrinsics, and
CPU/GPU resource entitlements independent of heap limits. Per-actor
priority, measured reductions, and CPU-share admission should be runtime
policy rather than static constants baked into `rt cooperative` syntax.

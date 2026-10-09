# Per-process event-loop single-flight contract

This PR models ROOT (grandaddy) and an independent supervisor (daddy) as
separate **logical process lanes** on shared carriers. One top-level guest
stack may run in each process at a time; nested ordinary function calls
increase stack depth, **not** the number of independent turns.

\`await\`/channel wait/select/cooperate must release a process turn by
returning a heap-owned continuation. A wakeup thread may only enqueue
that continuation; it cannot call the guest body. Timer expiry is likewise
notification-only. Timer callbacks must reenter through the owner lane.

A trusted supervisor is cooperative: a synchronous loop can monopolize
*its own* process. UNTRUSTED actor preemption is a separate fuel and
continuation-lowering obligation.

## Proposed short-lived main/timer lifecycle

\`main()\` returning terminates a short-lived process **without waiting**
for unrelated pending timers, callback Futures, or unselected channels.
The process closes its timer registrations, drops not-yet-admitted callbacks,
and rejects stale wakeups. This differs from Node.js's default ref'ed
setTimeout, and is similar to unref'ed timers.

A timer should eventually return a typed one-shot \`TimerHandle<T>\` that
exposes a \`Future<T>\` and/or a read-only \`Channel<T>\`; these are **not
yet source-language features**. The proposed \`timer 55s || -> {}\` spelling
and \`select { when readch to: _ { ... } }\` require parser, type system,
and standard-library contracts before claiming they execute.

If a process explicitly suspends on the timer via \`await\` or \`select\`,
the main continuation is still alive, so the timer is allowed to complete.
A timer may also fire and dispatch its callback during an unrelated
\`await\`, but that callback runs as a separate serial process turn.

Long-lived servers/supervisors can opt into explicit keep-alive scopes and
structured child tasks; ordinary timers alone must not silently own process
lifetime. \`std.process.exit(0)\` is an explicit process termination request,
not a global VM exit unless the owner is the entry/root process.

## Checked abstract properties

\`formal/process-turns\` is a dependency-free Rust exhaustive finite-state
model, **not** an implementation proof.

- At most one top-level guest stack per logical process, even across
  nested function calls, timer expiry, await, and stop races.
- Two distinct processes may execute simultaneously.
- Timer and Future completion publish readiness only.
- On main return, the process cannot retain runnable callbacks or an
  armed timer; late completion cannot resurrect it.
- An explicitly awaited timer can signal its waiting main continuation.
- A deliberately broken direct timer callback is rejected by the invariant.

Run:

\`\`\`sh
cargo test --manifest-path formal/process-turns/Cargo.toml -- --nocapture
\`\`\`

Full correctness additionally requires concrete runtime trace refinement,
fairness/liveness arguments, and elimination of legacy guest execution
bypasses (\`AsyncRuntime\` and generators, host interop), plus integration
of supervisor-owned mailboxes and process-level timer lifecycle hooks.

## Admission and duplicate one-shot reads

Once a supervisor receives a stop request, it must fence new user turns
immediately. A cooperative turn can finish its current synchronous stack
until the next exit/suspension/cancellation safepoint; suspension must then
exit rather than park a newly-cancelled continuation.

A one-shot timer readiness item is consumable once. Attempting another
`readch` on its closed one-shot channel should report channel closure
(or a typed terminal result), **not** wait forever. The abstract model
rejects an unsupported second pending wait; the library contract still
needs to define the exact source-level terminal result.

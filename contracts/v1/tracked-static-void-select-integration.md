# Tracked static Future<void> select — current-main integration (draft)

Status: **experimental / no successful exact-head CI**. Do not merge as production
behavior until cancellation, actor STOP and native-image release gates pass.

## Source distinctions

```ores
const Future<void> pending = nb select first {
    when readch inbox: int message -> { process(message); }
    when await finished: int result -> { process(result); }
};
await pending;
```

```ores
await nb select first {
    default: -> { process(1); }
};
```

A `StaticSelectExpr` is distinguished from (1) legacy detached procedural
`nb select { ... }` statements, and (2) dynamic
`nb select from cases` that returns a runtime selection result.
The current expression implementation supports only `Future<void>`.
All arms must finish without returning a value; a typed value-producing
`Future<T>` requires a different lexical arm-result representation.

Successful completion has **no guest-language value**. A null payload in the
Java runtime is an internal completion marker, never `undefined`,
`None`, or a legal `const void` binding.

## Safety invariants and current implementation

- The mainline `ChannelRuntime.SelectSet` arbitrates read/write/Future/default
  arms atomically. Losing Future arms detach their observer; their producer
  Future must not be cancelled merely because another arm won.
- The selected guest arm starts through `ActorRuntime.startActorTask` and
  runs on the owner actor scheduler; no guest code executes inside a Future
  completion callback or host selector.
- `OresFuture.fromCallback` now admits an operation-owned cancellation
  predicate. A pending select must be cancelled (and registrations revoked)
  before the exposed Future publishes cancellation.
- After a select winner has committed, consumer cancellation is rejected
  instead of prematurely reporting cancellation before lexical cleanup.
  If the owning actor subsequently STOPs, its cancellation rejection is
  converted to an explicit `incomplete cleanup` failure by the owning
  runtime. This is **not** proof that guest cleanup executed.
- The source task Future signals normal completion after the arm's lexical
  defer/finally handling. Producer terminal cancellation can be published
  after source retirement without rerunning pre-commit cancellation admission.
- Static type and ownership checks reuse modern select arm typing,
  continuation-bound capture safety, and MutexGuard restrictions.
- Tree shaking traverses static-expression arms and their referenced
  callables instead of silently omitting them.

## Hard blockers / evidence required

1. **Actor STOP after winner commit**. An owner STOP can suppress an
   already-enqueued winning arm. The staged `OresFuture` commit-gated callback
   now opts into actor-STOP failover: if consumer cancellation cannot roll
   back the committed winner, the actor publishes an **explicit exceptional
   terminal result** saying cleanup was not verified complete. It must never
   publish successful `void` or leave the result pending indefinitely.
   Targeted runtime unit regressions are committed, but STOP-versus-commit
   and force-revocation execution races have NOT been exercised against the
   exact source head. Authenticated cleanup continuation after STOP remains
   separate unimplemented work.
2. **Cancellation of running arms**. The branch currently *rejects* ordinary
   cancellation after a winner commits. Implement scheduler-owned deferred
   cancellation with deterministic cleanup and terminal publication if a
   cancellation request must also stop an active arm.
3. **Returned Future cancellation propagation and actor-lifetime accounting**.
   Check quota/queue overflow and observer publication failures. A committed
   winner must never strand a pending wrapper if the actor dies.
4. **Compiler execution**. Execute the checked-in source JUnit tests using
   GraalVM/JDK25. Full Maven verify, native-image AOT/hybrid/isolate, repeated
   concurrency stress, formatter idempotence and LLVM parity remain unproven.
5. **CI permissions**. `.github/private-ci-approved-repositories.txt` is
   empty. Do not copy private source to an outside repository, even a private
   funded organization, until protected-branch maintainers explicitly approve
   that exact destination in the checked-in allowlist.

**No prior green run validates this Git tree.** The historical PR #887 test
results validated different trees and must not be reported as this branch's
proof. This branch is a staged integration candidate, not a shipping feature.

# Actor error ABI v1 (GraalVM/LLVM)

The two backends carry the identical `actor_errors.ores` source declarations and
`actor-errors.csv` catalog. Numeric codes 1001–1020 are frozen; new codes must be
appended, not renumbered. These are **guest Error subclasses**, not host Java
Throwable/C++ exception subclasses. Parser acceptance and host catalog tests **do
not** establish executable guest exception lowering on LLVM.

## Supervisor and actor transport rules

- A supervisor receives a **copied, value-only envelope** (numeric code,
  bounded UTF-8 diagnostic, bounded opaque actor/supervisor identifiers).
- Never carry a live guest Error object, stack object, Java Throwable, C++
  exception_ptr, function, callback, actor heap pointer or mutable collection.
- Envelope diagnostics are bounded to 1024 UTF-8 bytes, malformed bytes
  are repaired, and terminal/bidi/zero-width controls are scrubbed. **This is
  not secret redaction**: the host constructor still accepts arbitrary text.
  Runtime callers must use a fixed, public-safe diagnostic (for example the
  frozen error key) instead of passing host exception messages, file paths,
  capability values, payloads, tokens or stack traces. A future trusted trace
  channel must enforce disclosure authority independently.
- Full traces require a trusted, explicitly requested diagnostic channel and
  must be generated on the originating actor before teardown. A guest-supplied
  `include_trace` request alone does not grant trace authority.
- `cancelled`, deadline, isolation, revoked capability, fencing and runtime
  invariants are control-plane terminals. They are not caught by `trap` and
  must not be converted to success by local `on_throw` / `on_raise` hooks.
- Recoverable send/mailbox/remote failures may be returned as typed
  `Result<_, Error>` by their APIs; they must not bypass admission/ownership.
- Supervisor priority remains host fatal/process exit > cancellation > panic
  > raise > throw; a lower-priority cleanup error cannot hide higher priority.

## Integration stages

1. **This change:** freeze guest declarations and code registry, expose
   bounded host-only error envelopes with direct JVM/native tests.
2. Wire source-level constructors, inheritance checks (only Error-derived
   values may throw/raise/panic) and typed Result/serialization to both
   compilers, using real guest conformance fixtures.
3. Connect runtime send/spawn/stop/supervisor paths and validate all actor
   handoff points, including cancellation and teardown, with native Image and
   LLVM end-to-end evidence.

Do not infer stages 2–3 are complete from existence of these files.

## Typed actor CONTROL slice (October 14, 2026)

Java/GraalVM now parses `raise expr` and `panic expr` as distinct, terminal
actor effects. The typechecker requires a non-suspending actor activation and
an owned, nominally proven `Error`-derived value. Outside actors, borrowed
values, `Any`/`Unknown`, structural imitations and effect statements inside
`finally` are rejected. `recover` and actor pseudo-handlers remain gated.

Inside an actor turn, the evaluator validates the guest instance, **drops
the instance**, and emits a stackless `GuestActorEffectSignal` holding only
`RAISE` or `PANIC`. Neither `trap` nor explicit source `catch` may
consume the signal, even through a wrapped host exception. On terminal actor
failure, the runtime publishes an immutable `ActorFailureNotice` to a bounded
nonblocking **host/supervisor-only CONTROL queue**, after releasing actor
lifecycle locks. The notice contains actor identity, structured-parent identity
(if present), effect kind and frozen actor-supervision ABI code 1009; its
diagnostic is a fixed protocol string, never guest or host exception text.
A full queue increments a dropped counter instead of blocking an actor.

The initial notification queue is **host-observable**, not yet an automatic
parent-actor `on_raise`/`recover` invocation. Scheduler-domain delivery,
structured recovery hooks, native guest LLVM lowering and end-to-end guest
actor tests are separate merge blockers. Tests have been added to exercise
real host actor turns and queue publication, but their execution is not yet
CI-verified. No live guest Error crosses actor heaps.

## Current hard blockers (October 14, 2026)

- Java recognizes a compiler-owned, non-generic nominal `Error`
  hierarchy. `ThrowStmt` now has **a restricted local lowering**:
  `try { throw new GuestError(...); } catch (e) { ... }` passes
  Error-only type checking and gives `e` the original Oreslang guest object,
  never a host Java `Throwable`. This is allowed **only** inside a
  non-actor lexical `try`; async/suspending callables, detached callbacks,
  borrowed errors and uncaught throws are rejected. `trap` does not consume
  this internal typed guest signal. Host exceptions keep their existing
  explicit-catch policy; cancellation and panic remain uncatchable.
- All statement visitors (ownership, borrow validation, capability admission,
  return/finally cleanup and incremental tree shaking/dependency scanning)
  now traverse the `throw` operand. `finally` may not throw.
- `raise`, `panic` and `recover` **remain disabled** until CONTROL
  publication, actor-local escalation and supervisor testing exist. The
  limited lexical throw slice is not a promise of complete exception semantics.
  `NativeGuestErrorRootTest` and `TypedExceptionalEffectAdmissionTest`
  cover the new slice, but **the added tests have not executed in CI**.
- The LLVM compiler supports only a source subset, and `src/core` is not an
  executable native core loader. Its sources do not establish LLVM guest
  inheritance, constructors, typed effects or catchability.
- The host `recoverable` value is **classification metadata**, not a proof
  that the runtime correctly catches, escalates, retries or supervises it.
- The cross-backend static verifier `scripts/verify_actor_error_abi.py`
  freezes source/host names, codes and declared policy; it does not exercise
  actor execution or the wire serializer. Do not mark the PR production-ready
  until executable guest inheritance + Error-only throw/raise/panic + transport
  and supervisor tests pass on both backends.


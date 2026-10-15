# Callback execution modes and lexical capture safety (staged implementation)

**Status: parser/AST support and fail-closed admission only.** This commit does
not enable async/suspends callbacks at runtime. Java/GraalVM, LLVM and other
backends MUST NOT advertise qualified-lambda execution from this PR.

## Orthogonal contracts

A callable's execution/effect mode is distinct from the callback-taking API's
delivery contract:

- `|x| -> {...}`: ordinary callable. Invoked synchronously by `map` or
  synchronous `emit`, or passed for later execution when the API contract
  explicitly permits later delivery. An unqualified lambda never promises that
  an API will invoke it immediately.
- `async |x| -> {...}`: proposed asynchronous callable returning a Future.
  The future implementation must schedule the guest body through an authorized
  scheduler before exposing this callable. In an actor, no direct producer
  thread execution or cross-actor bypass is permitted. The callback-taking API
  must accept an async callable type and publish its delivery policy.
- `suspends |x| -> {...}`: proposed potentially suspending callable whose
  source result is `T`, not `Future<T>`. Invocation may begin inline in the
  current admitted task and logically suspend later. The API must support
  suspension at that location. The qualifier alone does not promise deferral.

`async suspends` and duplicate qualifiers are rejected. `nlex` may compose
with either exclusive modifier. Keep these modes separate in callable ABI and
type comparison; overloading solely by async/suspends modifier is not supported
by current name+arity overload identity.

## Lexical and definite initialization

A local must be declared and definitely initialized at closure creation for
capture. Scheduling cannot make this source legal:

```ores
val callback = async |headers| -> { stdio.println(bar); };
let bar = "";
```

The same rejection applies to ordinary and `suspends` closures. The type
checker already resolves lexical names against currently declared bindings;
the regression suite pins this behavior. Future expansion must also enforce
ownership, sendability, mutation, actor state loans and cancellation fencing.

## Runtime admission still required before lifting the gate

1. Type qualifiers in `Fnc<...>` / callable descriptors; distinguish
   synchronous `Fnc<A,T>`, async `Fnc<A,Future<T>>` as a *mode*, and suspends
   effect independently from a plain synchronous Future-returning function.
2. Compile async lambda bodies to actual scheduler-backed continuations and
   ensure first guest-body instruction is not run inline on callback registration.
3. Capture, clone or move values according to owner/actor isolation. Do not
   detach mutable closure state across concurrent actors.
4. Admit suspends callbacks only where the caller can suspend without blocking
   an actor carrier; preserve state-loan and borrow lifetimes.
5. Explicit API callback delivery modes: inline emitter/map, deferred event
   callback, or potentially-inline host bridge. Do not infer registration
   timing solely from lambda syntax.
6. Prove cancellation, callback execution-lease ownership, no stale
   generation resurrection, overload/interop parity, and callback precedence
   around implicit preemption. Run compiled guest scenarios on JVM and
   GraalVM Native Image, followed by native LLVM parity gates.

This initial change intentionally rejects execution of qualified lambdas
until these requirements are fulfilled, rather than silently treating
`async |...|` as `|...|`.

## Callback-parameter delivery contracts (admitted syntax)

Callback delivery is now stored per parameter without changing ordinary `Fnc`
type identity. This is *declaration metadata*, not a proof of a function
implementation's runtime behavior:

```ores
pub fnc map_now(@callback(inline) Fnc<int, void> cb): void {
    cb(1);
    return;
}

pub fnc register(@callback(deferred) Fnc<int, void> cb): void {
    // Future scheduler-backed event registration will live here.
    return;
}

pub fnc adapt_legacy(@callback(maybe_inline) Fnc<int, void> cb): void {
    return;
}
```

The compiler checks that annotated parameters have callable types and rejects
structural/mutable callback parameters. Unknown/duplicate delivery modes are
parser errors. For public functions and public methods, unannotated callable
parameters produce compiler diagnostic **W-CB001**, available through
`OresCompiler.parseAndTypeCheckWithDiagnostics`.

**Proof boundary:** the compiler does not yet perform complete callback
invocation/escape effect inference. Until it does, a `deferred` annotation
must not be treated as verified proof that a specific user implementation
never invokes its callback inline. APIs offering a hard guarantee need
verified runtime-backed implementations and corresponding conformance tests.

## Borrow-checker protection

The ownership checker already checks lexical lambda captures and forbids a
borrowed value or `MutexGuard` from escaping into a closure. Move-only
captures transfer ownership, and reading the old owner afterwards fails.
The new delivery-contract tests pin these rules for
`@callback(deferred)` APIs. The annotation cannot grant permission to
share mutable state or bypass actor-local execution lanes.

Actor state still requires actor-owned `state` capabilities and mailbox
admission. An async callback has no privilege to retain a mutable loan
across await/select/preemption. Cross-actor execution requires detached
owned/sendable data rather than a shared writable reference.

## Strict suspension migration

Existing `suspends fnc` and `async fnc` modifiers continue to determine
declared suspension permissions, and ordinary direct calls to `suspends`
callables are already rejected in non-suspending contexts.

`@StrictSuspension` is an **opt-in, argument-free migration marker** that
additionally rejects direct `await`, blocking `select`, blocking
`readch`, and blocking `writech` inside an ordinary `fnc` or method.
The corresponding `async`/`suspends` declaration is allowed.

This is not yet global effect inference: legacy unannotated bodies, imported
unknown function effects, dynamic callable dispatch, and potentially
suspending callbacks still require hardening before enforcing this across
every Oreslang source file.

## CPU fairness is not logical suspension

Automatic reduction-budget preemption is a carrier handoff, **not** an
implicit actor callback-admission point. The synchronous continuation
retains logical ownership and priority over unrelated callbacks; it may
move to a new carrier. Explicit suspension may permit actor-local
interleaving only under the actor's configured admission/ownership
rules. A source assignment or function call is not automatically a safe
reentrancy point.

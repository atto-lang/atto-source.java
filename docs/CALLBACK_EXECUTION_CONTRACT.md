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

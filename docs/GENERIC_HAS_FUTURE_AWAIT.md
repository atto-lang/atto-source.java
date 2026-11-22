# Generic `HasFuture<T>` await protocol (stage 1)

An Oreslang `await` operand may have one of these statically checked types:

1. `Future<T>`: suspend on the native runtime future, producing `T`.
2. `HasFuture<T>`: project a future by calling `get_future()` exactly once,
   then suspend on it, producing `T`.
3. A generic class implementing `HasFuture<T>` directly or through a
   specialized base class or trait: resolve the concrete `T` statically.
4. `ActorRef<T>`: preserve existing actor-initialization barrier semantics,
   producing the original reference (not a termination result).

The nominal trait must declare exactly the following signature:

```oreslang
define pub trait HasFuture<T> as
    get_future(): Future<T>;
end
```

A class can implement the trait with the original
`class Name implements HasFuture<T> as` spelling or with
`class Name as implements HasFuture<T> as`:

```oreslang
define pub class Request<T> as
    implements HasFuture<T> as
    pub val Future<T> pending;
    pub get_future(): Future<T> {
        return self.pending;
    }
end
```

Trait projection must be a **concrete synchronous zero-argument method** that
returns `Future<T>`; `async`, `suspends`, generic-method and `mut self`
projections cannot implement `get_future`. The method must be public in this
stage to meet the existing nominal interface contract. These restrictions
apply to effective overrides even if a base class implements the trait. Implementations should normally return a stable
stored future; each occurrence of `await object` calls `get_future()` once,
so distinct occurrences can project anew.

The compiler must never infer awaitability from a coincidental method name,
accept mismatched generic result types, or use runtime reflection.
Oreslang actor instances do not implement this protocol in this stage:
`await ActorRef<T>` continues to use its separate readiness barrier.
The compiler must never expose an implicit mutable receiver via `await`. Runtime
projection occurs before scheduler suspension, with guard preflight before
invoking user-defined projection code. Future completion, errors and
cancellation use existing native future semantics. `await 3` remains invalid.

**Backend status:** Java/GraalVM implementation is proposed in this branch
and requires green real-guest tests. The targeted CI job has been added at
`.github/workflows/generic-has-future-await.yml` but Actions currently fails
before executing any job steps. A source-level assertion, workflow conclusion,
or a runner-capacity probe is not evidence of compiler correctness.
LLVM is not yet implemented; do not
claim cross-backend conformance until the LLVM frontend/lowering passes
the same positive and negative cases.

## Required follow-on coverage

- Prove one projection per await, including projection throwing and null results.
- Prove generic class/inherited trait specialization and incompatible `T` rejection.
- Test cancellation, exception propagation, pending futures, and guard-rejection
  side-effect ordering in both backends.
- Add `Task<T>` support through a real nominal `HasFuture<T>` implementation
  rather than an unrelated implicit coercion.

## Explicit limitations and future grammar

- Generic classes such as `class Request<T> ... implements HasFuture<T>` and
  concrete `HasFuture<int>`/`HasFuture<Future<int>>` specialization are in
  scope for this stage.
- A fully generic function with a `where F: HasFuture<T>` bound requires
  compiler-owned generic constraint metadata and static dictionary/witness
  resolution. Such `where` syntax is **not implemented** in this patch.
  Do not treat name-based recognition of an unbounded type parameter `F`
  as a substitute for a real trait bound.
- `await` unwraps one level: `HasFuture<Future<T>>` yields `Future<T>`.
  A second explicit `await` is necessary to obtain `T`.
- Module-qualified trait resolution, inherited class implementations, and
  inherited generic trait specializations have dedicated compile-time tests.
- Generic inheritance traversal is path-local and checks nominal declaration
  identity, not only fully instantiated type equality: `A<T> : A<Array<T>>`
  must not cause unbounded recursion. Diamond paths are independently visited;
  identical `HasFuture<T>` witnesses may agree, conflicting `T` must fail.
- The ownership pass must project the awaited result to `T` with the correct
  Copy/move classification; it must not inherit the wrapper's ownership kind.
  A named `HasFuture<T>` receiver is borrowed immutably for the synchronous
  projection, not moved by the await operator. A later await on the same
  capability may call `get_future()` again; implementers decide whether this
  returns the same future or a new operation.
- Explicit cancellation ownership and zero-copy Task/ActorResponse integration
  remain follow-on implementation tasks.

## Reproducible verification

On an authorized checkout of the exact PR head, using GraalVM Java 25:

```sh
mvn -B -ntp -Dtest=GenericHasFutureAwaitTest test
mvn -B -ntp '-Dtest=AsyncAwaitLanguageTest,GenericsAndOperatorsHardeningTest,SuspendsContractLanguageTest,BindingMutableReceiverContractTest' test
mvn -B -ntp test
```

Only mark the feature merge-ready after actual job steps, logs, and executable
guest test results have been inspected. Do not mirror this private source to
a public repository or to a cross-org repository without authorization.

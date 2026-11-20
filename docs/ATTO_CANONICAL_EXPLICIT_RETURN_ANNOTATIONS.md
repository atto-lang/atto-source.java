# Canonical explicit named-callable results: Java parser admission

Authority: `atto-lang/atto-spec/language/explicit-callable-return-types-v1.md`.

## Source contract

In explicitly canonical AttoLang parsing (`parseStrictAtto` and the
versioned Atto callable parser), every **named** callable must declare
its result in the header. Concrete `-> T`, `: T`, `-> void`,
`: void` and the reserved opt-in `infer` are syntactic annotations;
a return-first `T <- name(...)` or `T• name(...)` is already explicit.

This applies to free/module `fnc`/`routine` declarations, class/actor
methods and static functions, external actor receiver declarations and
interface callable requirements. Named lambda-style declarations
(`fnc name = |...| -> ...`) must not default to `void` when the
result annotation is absent.

The unversioned legacy `Parser.parse` retains its historical implicit
`void` syntax. Anonymous contextual lambdas and constructors are
different grammar categories and are unaffected.

`@Ret(T)` is treated as an explicitly supplied result in the legacy
annotation form, subject to existing compatibility checks. This
milestone **does not implement whole-program return inference**:
accepting `-> infer` syntax is not evidence that all inference or
guest dispatch paths work. The canonical checker must reject an
unsupported inference profile rather than silently pick `void`.

## Security / migration

- Reject omitted canonical named-callable result with deterministic
  `E_RETURN_ANNOTATION` before typechecking and guest emission.
- Do not synthesize `void` after parsing and accidentally suppress
  non-void return, exception, Task or actor effects.
- No change to ABI, effects, actor ownership, host imports or capability
  enforcement.
- Legacy guest compatibility is explicit; do not guess language profile
  from a filename or silently migrate unannotated callables.
- This PR is stacked on the canonical class-field-v2 parser profile
  patch in `atto-source.java#63`.

## Verification

```sh
mvn -B -ntp '-P!native-thread-unix' \
  -Dtest=AttoCanonicalCallableReturnAnnotationTest,AttoCanonicalImplicitValClassFieldTest \
  -Dsurefire.failIfNoSpecifiedTests=true clean test
```

Require broader callable, constructor, interface and actor parser regression,
and actual compiled positive/negative guest execution before release parity.
A source grammar JUnit is not executable guest evidence. Verify exact Git SHA,
tree and nonzero CI steps; zero-step workflow results are `NOT_EXECUTED`.

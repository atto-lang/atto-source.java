# Java `trap` JVM-fault admission hardening (candidate)

## Scope

A fault emitted by the JVM or trusted host runtime must not silently cross
`trap` as an ordinary guest `Err(Error)`. The pre-existing
`TrapFailurePolicy` walked bounded cause/suppressed graphs but treated JVM
`NullPointerException`, `ClassCastException`, collection/array faults,
`ArithmeticException` and monitor faults as ordinary catchable exceptions.

This patch rejects those categories from **both** `trap` and explicit
guest `catch`, including when wrapped in `CompletionException` or attached
as suppressed cleanup failure. Checked integer overflow is not a catchable
ordinary source throw. Other existing policy distinctions, including explicit
guest catch of capability denial/timeouts and fail-closed cancellation/panic,
are unchanged.

## Implementation boundaries

- This is a JVM host/runtime classification fix, **not** typed Atto source
  `throw Error(...)` lowering.
- `src/main/java/dev/oreslang/parser/Parser.java` still explicitly rejects
  `throw` / `raise` / `panic` statements from this compiler profile. The
  canonical `atto-spec/language/trap-result-v1.md` requires original source
  Error identity and subtype/payload retention once source throw is supported.
- The existing Java evaluator `TrapError` currently sanitizes a Java failure
  into a printable value. This is not proof of guest Error identity.
- More VM-originated failure classes must be inventoried. An allowlist of
  explicitly typed source-throw wrappers will eventually be safer than
  classifying arbitrary Java RuntimeExceptions by exclusion.
- Separate the Rust-authored source-to-Wasm compiler from the native
  LLVM-to-Wasm integration bridge when claiming parity.
- Do not claim cross-tenant hostile-code isolation, Graal Native Image guest
  execution, or AOT/zero-GC from this patch.

## Verification

On the source commit, require `TrapFailurePolicyTest`,
`TrapResultSemanticsTest` and `CanonicalCallerTrapParityV1Test` against the
real Maven/JDK25 graph in an **executed**, nonzero-step, exact-head job. An
independent focused Java21 smoke was performed against the exact policy file
blob `d3bc18063a93a223dcd5f2cd15f7dc44d685cb8a` with minimal stubs for
dependencies: 45 assertions passed; this is **not** the repository JUnit suite
or a Graal guest receipt.

Do not merge or promote based on zero-step Actions, source-only checks,
or this focused smoke. Await independent security review and the repository
review-cycle governance.

## Overlap/next work

- `atto-source.java#52`: host-only `int.parse` Result foundation; independent,
  not superseded by JVM fault classification.
- `atto-source.rs#275` and `atto-platform-conformance#68`: bounded actual
  native/Wasm source `trap → Result` reference and immutable test evidence.
- The next Java source-language PR must implement typed `throw Error(...)`
  parsing/typechecking, source-only catch boundaries, original Error identity,
  cleanup-aware `?`, and adversarial negative tests. The currently pinned
  `05-ordinary-throw.atto` conformance case is intentionally still rejected.

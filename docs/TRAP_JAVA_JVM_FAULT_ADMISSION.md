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

## Strict Atto source-profile fail-closed admission (2026-11-20)

An additional risk existed outside the JVM failure-classifier change.
`Parser.parseStrictAtto` accepted the legacy `trap` expression and
`trap fnc` modifier, even though this Java backend still rejects source
`throw Error(...)`, has no guest-owned Error identity, and constructs
sanitized `TrapError(type, message)` instead of preserving the caught
guest object. That could make strict AOT front-end admission appear to
implement normative `atto-spec/language/trap-result-v1.md` when it does not.

The strict-profile lexer preflight now fails **before AST emission** for
both `TRAP` and `THROW` tokens with deterministic
`ATTO_STRICT_TYPED_TRAP_UNSUPPORTED`, including token location.
The rule is applied only via `Parser.parseStrictAtto`, which is called by
`OresCompiler.parseAndTypeCheckStrictAtto` and strict graph/build paths.
No unversioned/legacy `Parser.parse` or ordinary `trap fnc`
behavior was silently changed.

Added `AttoStrictTrapSourceAdmissionTest` with five focused regression
cases: strict expression trap rejection, strict trapped-callable rejection,
source-level throw rejection, string/comment noninterference, and separate
legacy compatibility. The exact-head CI includes the new test class and
the parser path. **Tests authored; full repo Maven/GraalVM/JDK25 tests
NOT_EXECUTED at the current source head**. This is not a positive guest
implementation; conformance `05-ordinary-throw.atto` remains a blocker.

The real enabling change must add (a) compiler-recognized Error instances
with nominal subtype, payload and stable object identity; (b) source
`throw Error` AST and checked effect admission; (c) ordinary exception
unwinding through `try/catch`, `trap`, `defer`, `finally` and the
`?` early-return path; and (d) original Error-valued `Err(error)`
without the host `TrapError` sanitizer. The strict fail-closed gate may
be removed **only** alongside positive and negative guest compiler tests
demonstrating those obligations. Source-branch CI and private carrier
governance remain independently blocking.

## Typed ordinary guest Error runtime foundation — 2026-11-20

The draft now contains a **runtime-only, compiler-inaccessible** foundation
for a future real typed `throw!` and caller-side `trap!` path. It does
not replace the fail-closed strict-source admission gate.

- `GuestErrorValue`: immutable nominal base `Error` payload with JVM
  reference identity; it is not a Java Throwable, has no overridden
  equals/hashCode, and implicit `toString` does not disclose the message.
  The first bounded slice accepts a non-null message of at most 4096 Java
  UTF-16 code units, rejects oversized input rather than silently truncating,
  and does **not** yet support source Error subclass instantiation, guest
  traces, structured payloads, capability transport or TenantVM transfer.
- `GuestOrdinaryThrow`: distinct stackless JVM control marker holding
  the original `GuestErrorValue` reference, not a stringified copy or host
  Throwable cause. A fresh signal can represent a rethrow without copying
  the underlying Error object.
- `TrapFailurePolicy`: directly raised guest signals remain ordinary
  throw/catch candidates, with fatal host VM classes, cancellation and
  panic excluded. A signal hidden inside an async/host wrapper now **fails
  closed**, rather than becoming a sanitized Err with lost Error identity.
  Bounded source trace markers remain diagnostic only; suppressed
  cancellation or other terminal faults still prevent capture.
- `OresEvalRootNode`: the existing direct and suspendable try/catch
  handlers bind the **original Error value** for these typed signals.
  The `trapErr` branch puts that same object in the Err slot. Unversioned
  legacy Java failure translation continues to use `TrapError` and is
  not misrepresented as typed source Error conformance.

### Evidence, exact Git blobs and scope

Runtime files tested locally and checked against GitHub:
- `GuestErrorValue.java` `64d8af2734f79c95d3faef19d297ac3125ba3c86`
- `GuestOrdinaryThrow.java` `0ad3b4bbe7c0543e4b4e6d51ef5c26ad42acf884`
- `TrapFailurePolicy.java` `20109dbbb153309b28fa22c82ce105f4f224efe9`
- `GuestOrdinaryThrowTest.java` `3aa9d31570859dc3c46e88d6a78c0189de0558f3`

**Local Java 21 execution:** 14/14 direct smoke assertions passed
with the exact two guest Error runtime blobs. The actual six committed
`GuestOrdinaryThrowTest` methods also compiled and **6/6 passed**
using a local stand-in JUnit assertion/annotation shim and minimal
`SourceBoundaryTrace` / `LogicalStackOverflowException` stubs,
with the Error and policy files **Git-blob-exact**. This is **NOT**
real Maven/JUnit test execution against the complete repository graph,
nor JDK25/GraalVM guest execution, Native Image or fully checked
AttoLang compiler semantics.

The exact-head workflow now includes the two new runtime files, the
evaluator, the test class and the new named Maven test selection.
Material source-runner verification remains **NOT_EXECUTED**.

### Syntax overlap and remaining end-to-end blockers

- [Java syntax PR #194](https://github.com/atto-lang/atto-source.java/pull/194)
  owns lexer recognition of `trap!`, `throw!`, `raise!` and `panic!`.
  [Spec PR #146](https://github.com/atto-lang/atto-spec/pull/146)
  owns canonical bang spelling and historical migration.
  Their sources are not copied into this runtime patch; integration
  must preserve contiguity and unary-not distinctions.
- Strict Java source `trap!` and `throw!` still **reject before AST
  admission** until checked nominal Error/subtype construction, source
  exceptional-effect AST, escape/throws typing, call-chain propagation,
  cleanup-aware `?`, guest Error identity through ordinary catch, and
  raise/panic/actor supervisor behavior have compiled proof.
- The initial immutable Error carrier has **no TenantVM affinity or
  cross-tenant sharing authority**. It may only be used by the trusted
  internal runtime; adding guest-visible constructors and actor transfers
  requires tenant-bound ownership/capability gates and an audited nominal
  ABI. The host marker itself is not exposed to guest code.
- End-to-end compiler guest tests, exact-current-head JDK25/GraalVM
  execution, cross-backend Rust/Wasm differential proof and independent
  security/merge review remain mandatory. Keep this PR DRAFT.

# Native Atto `using telemetry.span` — Java compiler/runtime milestone

**Status (2026-11-12): source-implemented synchronous compiler-owned scope; exact-head GraalVM guest execution NOT VERIFIED. Keep PR DRAFT.**

## Compiler/runtime path

The parser recognizes `using telemetry.span("operation") as span { body }`
without adding a new language keyword. It stores the original syntax on a
compiler-marked `Ast.BlockStmt(usingTelemetryScope=true)`, still carrying
historical synthetic lexical/finalizer nodes for provenance and static
analysis. Those synthetic calls are **never executed as guest code**:

1. `TypeChecker` validates the marker, one literal operation of 1..256 strict
   UTF-8 bytes, no guest `telemetry` module/import impersonation, and a
   synchronous, non-generator, non-actor lexical owner. It validates only the
   typed direct observers `set_attribute`, `add_event`, `record_error`,
   `set_status`; alias escape, shadowing, manual close, and unrelated calls
   fail closed. This is a restricted compiler-owned protocol, **not** a
   general `ScopedResource` implementation.
2. `OwnershipChecker` checks the real user body and typed observer arguments
   without constructing a movable guest `SpanScope` owner.
3. `OresEvalRootNode` intercepts the marker, creates/reuses a logical
   `NativeTelemetryScopes.Carrier` held in private lexical `Env` metadata,
   obtains a private `SpanToken`, executes the user body behind a tail-call
   barrier, and closes the token in a Java `finally`. No token is added to
   a guest-visible `Env.Slot`. An outermost scope retires its carrier.
   Normal return and nested unwinding use the same runtime-owned close path.
4. The native Java core enforces 64-deep LIFO, exact-once close, cross-carrier
   denial, strict UTF-8 operation validation, optional host-only dormant
   recording toggles, and bounded typed observer accounting. **No production
   span attribute/event export, OTLP, or authorized Truffle attach exists.**

The native per-context tracer is **disabled by default**. The synthetic
`close_scope()` call in the AST is not a guest operation. This remains a
source implementation candidate until the full suite actually executes.

Transport `using request.borrow()` remains a separate `UsingBorrowStmt`
with its existing fail-closed loan gate; no implicit transport borrowing
authority is added here.

## Exact-head execution gate

`.github/workflows/atto-telemetry-using-exact-head.yml` runs on GraalVM
JDK 25 and requires at least **40 executed cases**:

- 26 `UsingTelemetrySpanScopeTest` methods (compiler + real `.atto` guest
  output on restricted synchronous paths, negative impersonation/escape and
  typed-observer admission tests)
- at least one `UsingRequestBorrowScopeParserTest` transport-loan regression
- 13 `NativeTelemetryScopesTest` host lifecycle cases

Surefire must contain all three named suites, with nonzero executed counts,
zero failures, zero errors, and zero skipped cases. No synthetic fixture or
source-only assertion can substitute for that receipt.

**The 40-case Java gate is NOT EXECUTED.** The preceding Java source workflow
[run #43334977144](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43334977144)
failed with **zero job steps**, before compiler or test execution. A newer
source-head run (if it appears) is not a pass until its job steps and receipts
are checked. No private Java source has been transferred to a new runner.

## Security and correctness limitations

- A compiler-marked `BlockStmt` is still an AST-lowering bridge, not a
  dedicated `UsingSpanStmt` with full source provenance. Formatter
  round-trip/source span fidelity remains unproven.
- The current direct-observer subset is deliberately narrow; nested observers
  and transitive effect calls require identity-bound MIR/ownership proofs.
  Until those effect summaries exist, arbitrary user call expressions inside
  the scoped body fail closed during type checking.
  Observer calls are *counted*, not yet exported as trace metadata.
- `await`, `yield`, `select`, source tasks, generator/async/suspends
  callables, and actor-scoped execution remain blocked until continuation-owned
  frames and scheduler migration/cancellation cleanup are integrated.
- Typed `throw`, `raise` and `panic` are still parser-rejected in this
  compiler. Generic Java finally code is not executed actor-supervision proof.
- The public Java method name `setEnabledFromTrustedHost` is **not itself**
  host authentication. Keep recording disabled without an explicit authorized
  control plane; Truffle attach/detach and source-tag/deopt proof remain P1.
- The Java path is not equivalent to the LLVM restricted AOT subset until
  exact-head executed guest validation and independent code review pass.

## Existing LLVM acceptance receipt

[LLVM PR #1716](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1716)
has an executed, source-blob-verified restricted AOT receipt from source head
`3466e3702094eaeb413382c16cdadd9a7a56161f`:
[approved private CI #43325381462](https://github.com/streempilot-test/browser-studio-e2e/actions/runs/43325381462).
The verified 48-file subset covered five positive/five negative compiler
guest cases, native Clang O0/O2, and trusted host off/on/off/on activation of
**precompiled dormant AOT probes** without a process restart. This does not
prove dynamic JIT insertion or general async parity.

## Runner authorization

The Java source policy `.github/private-ci-approved-repositories.txt`
requires independent @ORESoftware approval, immutable repository-ID review,
and protected-main/V3 attestation **before** any private source transfer.
Review remains requested on Java PR #2993. Neither candidate status, existing
LLVM carrier approval, nor Actions minutes exhaustion authorizes exporting
the Java repository to an unapproved organization.

**Next gate:** obtain approved Java execution receipts at the exact PR head,
fix genuine compiler/runtime failures without skipping tests, then integrate
continuation/actor cleanup and a separately authorized Truffle live controller.

# Callable generic defaults: compatibility-preserving first slice

**Status:** draft, unverified. This branch is stacked onto named/partial
call generics PR #910. It is not a claim of complete language support.

## Implemented in this stage

- Source parser accepts defaults on **top-level/module functions**:
  `fnc foo<A, B=int, C=B, D=Array<C>>()`.
- `Ast.GenericParameter(name, defaultType)` preserves type-level defaults
  in a dedicated ordered list. Existing `genericParameters(): List<String>`
  and the prior 15-argument `FunctionDecl` constructor still work.
- Type checking validates the declaration before execution: duplicates,
  forward/self references, invalid inferred slots, and unsupported named
  metadata are rejected even when a call would override a default.
- Explicit named/positional bindings remain fixed; infer value arguments
  before applying defaults in declaration order. A user-supplied `_`
  demands inference and never falls back to a default.
- Ownership specialization follows the same substitution order and cannot
  silently ignore declared defaults in ordinary/qualified function calls.
- Exported function defaults are serialized into incremental ABI fingerprints.
  Changing a public generic default invalidates the imported contract, while
  private-only defaults do not perturb the exported ABI.
- Tests cover parser AST preservation, five-parameter dependent defaults,
  explicit overrides, inference priority, failure on uninferable holes,
  forward/self references and backwards-compatible AST constructors.

## Deliberate, fail-closed boundaries

- `B=Unknown` and `B=Any` are **rejected**, rather than treated as
  unresolved nominal types. They require the safe source-level Any/Unknown
  contract and ownership gates to land first (see Java PR #844).
  Ordinary resolved concrete types and prior generic parameters are
  supported in stage 1.
- The signature parser change applies only to functions. Generic methods,
  classes, interfaces, constructors and aliases still require the subsequent
  structured-AST migration before syntax/default support may be claimed.
- Structural generic method source names remain unavailable after alpha
  renaming; named structural arguments are rejected, not guessed.
- LLVM and oreslang-format do not yet understand this syntax.
- Compiler AST metadata is not proof of runtime generic reification.
- No cross-actor or async generic boundary is weakened.

## Merge and verification gates

Run the exact branch SHA with GraalVM/JDK 25:
`mvn -B -ntp -Dtest=FunctionGenericDefaultsStage1Test,NamedPartialGenericCallsTest,GenericCallArgumentsTest test`
followed by the entire Maven verify suite and runtime/ownership regressions.
Verify generated library/linking signatures preserve default metadata and
cross-module ABI compatibility. The underlying PR #910 must be verified
and merged before this stacked PR can target main.

The source org CI has so far failed before runner steps were allocated.
An existing private alternate-org mirror was discovered, but its source
tree was stale and the connector blocked part of a cross-org workflow
copy. **No exact-source mirror or passing verification is claimed.**

## Subsequent security audit (2026-10-14)

- Generic defaults must reference only a prior generic parameter or an actual
  compiler-recognized builtin, class, interface or type alias. An arbitrary
  unresolved nominal name (including nested names in Array/Option) cannot be
  treated as a concrete default. This restricts stage-one defaults more than
  Oreslang's otherwise permissive ordinary nominal-type resolution.
- Reject invented compiler-only `$...` type descriptors in AST-generated
  function defaults. Existing, well-formed synthetic record/tuple/borrow/
  union/sequence descriptors still undergo recursive validation.
- Default validation executes at declaration admission, not lazily at first
  use; caller-supplied explicit overrides do not hide invalid defaults.
- Added tests for malformed and nested unresolved defaults, default ordering,
  and generated-AST synthetic-name injection.
- No actual Maven, GraalVM, or LLVM tests have executed for these commits;
  source-organization fast-ci continues failing with no runner steps.

# Parameter presence v3 — admission audit (2026-10-19)

Status: parser/AST staging and fail-closed semantic admission, NOT executable
guest-language defaults, Option-sugar or optional arguments.

## Code scope of this stacked draft

- AST Param tracks `optionTypeSugar` and `omittable` separately, keeping
  compatible constructors for legacy compiler producers.
- Canonical parameter syntax: `T? name` marks lexical Option<T> sugar;
  `T name?` marks omittability; `T? name?` carries both. Only formal
  declarations parse this sugar at this milestone: fields, returns, nested
  generic type arguments and function types remain separate feature gates.
- Hidden instance `self` receiver is outside value-formal parameters.
  Calling `self.foo(self, 5)` still supplies two ACTUAL expressions.
- Existing `@DefaultArgVals(name: literal)` parser preserves named literal
  metadata with no executable guest expression. Unknown keys, duplicates,
  reserved `self`, malformed syntactic literals, unsafe mutable/structural
  defaults and bare omittable scalars without defaults fail admission.
- Valid staged syntax is rejected by UnloweredDefaultArgValsGuard in local
  TypeChecker, imported class type signatures, OwnershipChecker (local and
  imported), and Truffle evaluator construction. Synthetic lambda ASTs with
  staged optional flags are also constructor-rejected.

## Verified independently (not compiler execution)

A source-free Python semantic reference was run locally against 17 positive /
negative call-shape cases, an explicit call-site self case, and 25,000 seeded
randomized call shapes. The reference suite passed; 244 randomized shapes were
accepted with zero detected provenance violations. This proves only the
reference model, not JVM/Graal/LLVM behavior or the latest Git tree.

JUnit regression sources were added to the PR branch but have NOT been run by
the repository's Maven/JDK25 test suite. The environment used for the reference
model has Java 21 but lacks the source repository's full Maven build and JDK25
dependency/runnable Git checkout.

## Security of alternate Actions organizations

Source repo `.github/private-ci-approved-repositories.txt` on protected main
has NO authorized destinations. Draft security review #1326 proposes
`sagitta-stack/sagitta-infra` but is still unmerged and without approvals.
Do NOT export private compiler source, including via Git blob/tree APIs, until
that source-org policy has been approved on protected main, target access and
visibility reviewed, and the source PR head/tree independently pinned.

After approval, a safe cross-org run requires: private destination, exact
per-path Git mode/bytes and complete tree SHA equality, no stored source
checkout token/secrets, destination-local Actions checkout, immutable exact
SHA test runs, source/artifact retention review, actual job-step/log evidence,
full Maven/JDK25 plus GraalVM/LLVM (when supported) positive/negative tests,
and teardown of temporary mirror refs.

## Remaining delivery gates

1. Versioned feature flag and imported signature/ABI fingerprint: v1/v2
   T? had different staged semantics; never reinterpret old modules silently.
2. Typed nominal proof for intrinsic Option and aliases; default literal
   evaluation/type checks, overflow, nested Option, ownership and capability
   restrictions. Do not derive Option legitimacy from a raw string name.
3. Select immutable checked callable targets with conservative supplied-arity
   interval collision rejection across inheritance and interfaces.
4. Bind provided source Expr once in lexical order; record raw call item,
   formal slot, evaluated expression origin; omissions/defaults add no Expr.
5. Allow source execution only after type/ownership/effects/defaults proofs
   are bound to a single selected declaration/ABI and consumed by every
   interpreter/AOT backend; otherwise fail closed.
6. Real executable source-based CI, formatter roundtrip, actor boundary,
   constructor definite assignment, imported overrides and Kotlin/Java
   annotation interop parity where appropriate.

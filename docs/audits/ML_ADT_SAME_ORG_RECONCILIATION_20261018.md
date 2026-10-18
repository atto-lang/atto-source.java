# Nominal ADT + actor-state compiler integration: conflict-safe staging

**DRAFT — INTEGRATION=BLOCKED; JAVA_GUEST=NOT_EXECUTED; DO NOT MERGE.**

This same-repository branch derives from Java ML guest head
`aa64894383446c2723ff971400276bbfb8b2555d` (original Git tree
`2b1cc32830e8159e37ba3e1bad26bba20024dbaa`) and stages ten previously
absent Java ADT support classes from nominal ADT head
`27ccb069b5e3cc2d293c5ba537d70a4fbedb7c67`.
Initial staging preserved the complete original guest source tree. Subsequent
focused edits reconciled **four shared syntax-layer files** (`Ast.java`,
`Token.java`, `Lexer.java`, `Parser.java`) by adding nominal enum and match
pattern declarations while preserving existing actor-state syntax. Three
isolated V8 host-only parser bridge tests passed; Java compilation is still
**NOT EXECUTED**. These four files are only partially integrated: their
typechecker/runtime call sites remain dependent on further work.

All 17 original overlapping core files require a full semantic review, and the
other 13 shared compiler/runtime files remain unchanged from the ML guest tree.
Never wholesale-replace either version with the other:

- `src/main/java/dev/oreslang/ast/AnnotationExpander.java`
- `src/main/java/dev/oreslang/ast/Ast.java`
- `src/main/java/dev/oreslang/nodes/OresEvalRootNode.java`
- `src/main/java/dev/oreslang/parser/Lexer.java`
- `src/main/java/dev/oreslang/parser/Parser.java`
- `src/main/java/dev/oreslang/parser/Token.java`
- `src/main/java/dev/oreslang/runtime/ActorRuntime.java`
- `src/main/java/dev/oreslang/runtime/CapabilityChecker.java`
- `src/main/java/dev/oreslang/runtime/ChannelRuntime.java`
- `src/main/java/dev/oreslang/runtime/IsolatePolicy.java`
- `src/main/java/dev/oreslang/runtime/NativeHttp.java`
- `src/main/java/dev/oreslang/runtime/OresContext.java`
- `src/main/java/dev/oreslang/runtime/OresFuture.java`
- `src/main/java/dev/oreslang/runtime/ProcessExitSignal.java`
- `src/main/java/dev/oreslang/runtime/RuntimeGarbageCollector.java`
- `src/main/java/dev/oreslang/types/OwnershipChecker.java`
- `src/main/java/dev/oreslang/types/TypeChecker.java`

## Gates before any certification

1. AST, lexer and parser must support `EnumDecl`, `parseTaggedEnum()`,
   `match(ENUM)`, and maintain `with state.write` plus critical `using state`.
2. Type and ownership checkers must enforce nominal variants, foreign-type
   rejection, nested exhaustive `Result` matches and actor state-loan seals.
3. Runtime must implement nominal constructors/matches, actual actor lifecycle
   and error/result output; no JS model is a substitute.
4. Run `node --test tests/adt_ml_staging_source_contract.test.mjs`
   (host-only) then `node scripts/verify-adt-ml-integration.mjs`
   (intentionally exit 78 until shared compiler files are reconciled).
5. Run JDK25/GraalVM `TrainingErrorNominalResultGuestTest` plus
   `MlActorStateScalarGuestTest` and full Maven on one approved exact
   SHA/tree; subsequently LLVM parity and actor/thread tests.
6. Until verified, keep this staging branch **DRAFT**, not a merge claim.

Open confidentiality incidents #22/#77 prohibit further cross-organization
private-source copies without independent clearance. Runner approval and billing
remain separate from compile correctness. No private source exported.

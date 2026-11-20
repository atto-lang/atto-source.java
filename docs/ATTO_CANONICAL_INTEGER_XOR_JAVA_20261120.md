# Java/Truffle Atto canonical caret and integer XOR reconciliation

Status: **Draft candidate, guest/CI NOT_EXECUTED at this head**. Normative decision: [atto-spec #143](https://github.com/atto-lang/atto-spec/pull/143). Forge companion: [atto-source.forge #102](https://github.com/atto-lang/atto-source.forge/pull/102).

- `^ expr;` remains the return shorthand only in explicitly permitted callable contexts; `a ^ b` is denied with a dedicated replacement hint. The Java lexer still emits separate CARET tokens, and adjacency of `^^` is verified by the parser.
- `int ^^ int` is signed 64-bit bit-pattern XOR, precedence `|` < `^^` < `&`. The former `bool ^^ bool` behavior is **removed**; use boolean inequality or a checked named Boolean XOR API. No implicit bool-to-int conversion.
- The strict type-checker, compile-time constant evaluator, normal Truffle evaluator and suspended-actor continuation path agree: XOR is eager, left-to-right, integer-only, with one observable guest result. The runtime keeps no legacy `^ ` AST handler; injected legacy syntax cannot bypass the type checker.
- Positive and negative tests live in `LogicalPunctuationOperatorTest`; the shared truth-table fixture is migrated from boolean `^^` to integer `^^`. This changes cross-backend expectations and therefore requires new conformance/formatter review.
- **Not certified:** downstream backends, general checked arithmetic beyond XOR, full Maven/JDK25/GraalVM Native Image execution, cross-backend imported modules or TenantVM hostile guest sandboxing. No change to I/O `<< >>`, numeric shifts or `Math.pow`. Run Java exact-head compilation, true Truffle guest tests, and independent review before merging.

# Java actor critical-state preflight compiler fix — 2026-10-29

Status: **draft source correction, not a compiled or executed native actor-state feature**. Stacked directly on draft PR #2259, which is in turn stacked on #2257 → #2253 → #2248. Do not merge independently of its ancestors.

## Exact finding and correction

The PR #2259 `ActorCriticalLoanPreflight.checkExpr(Ast.Expr, String, Ast.CriticalLoanMode, Map<String, Ast.TypeRef>)` implementation contained **seven** recursive invocations with a nonexistent fifth `effects` argument. Java rejects the compilation of this visitor before any loan or JUnit test can execute.

This PR removes only those seven extraneous arguments in the recursive callback validator, including binary, unary, conditional, and assignment-value traversals. The `scanOutside(..., effects)` overload remains intact; it still checks declarations against the method's typed state-effect bound.

It also strengthens the **diagnostic-only** preflight in two ways:

1. Reject direct reads of `self.<sealed_field>` / `self.state` outside critical loan callbacks. Reject attempts to treat `self.handle_state` as a first-class callable value.
2. Reject unexamined statement and expression AST kinds in sealed-state methods rather than treating unknown/new AST kinds as safe by default. This conservative restriction is intentional and must be relaxed **only** with an explicit typed/effect/borrow proof for each new node kind.

Existing focused JUnit tests now add direct-field and escaped-intrinsic negative cases. They are **committed but have not run**. The author-side review matched all seven previous erroneous recursive call sites to four-argument calls, preserved the separate `scanOutside(..., effects)` signature, and checked exact Git blobs. This is a source inspection, not `javac`, Maven, or guest execution.

## Remaining required functionality

This does not implement the physical actor-owned arena, nonparking reader/writer permits, exact loan lifetime, `await/select` frame pinning, capability issuance, protected-state reentrancy policy, shutdown fencing, or authenticated native model ingress. `TypeChecker.checkClass` continues to reject sealed-schema actors with the explicit **cannot execute before compiler-enforced handle_state loans and arena isolation** error; do not remove that protection on the strength of this PR.

For the native AI coordination demo `ContextAssemblyActor`, compilation/runtime acceptance remains blocked. The demo intentionally quarantines raw context so signed host evidence cannot be bypassed via the old `add(...estimated_tokens)` method. The bridge remains a host-only research reference, not an authenticating native transport.

## Exact verification plan (not yet executed)

1. On a trusted source-local runner with a clean exact branch SHA, build `oreslang-source.java` using pinned JDK 25/GraalVM and Maven. Assert `javac` compiles `ActorCriticalLoanPreflight` without unresolved identifiers or recursive method arity errors.
2. Execute JUnit `ActorCriticalLoanPreflightTest`, `SealedActorStateAdmissionTest`, `ActorCriticalLoanModeSyntaxTest` (if present), and existing actor/borrow suites. Preserve complete JUnit XML and nonzero executed steps.
3. Parse positive scalar loan fixtures and assert deterministic callback preflight results. Require targeted negative diagnostics for direct self-field access, escaped loan, wrong read/write effect, unknown field, await/call, and forged `handle_state` method.
4. Separately prove `TypeChecker` **still fails closed** on typed schema actor execution pending runtime lowering. A parser/preflight success is not an executable native guest result.
5. Once independently compiled runtime lowering exists, execute native Java/Graal guests, then LLVM, then .NET/WASM/Forge, including restart, cancellation, multi-tenant isolation, and real verified model-ingress data checks.

Known source org CI has repeatedly reported failed jobs with **zero steps**, so runner admission must be verified independently. Do not export private sources to another org just to obtain runner minutes without separate authorization.

## Exact-head source-local verification workflow and confidentiality gate

This branch also adds `.github/workflows/actor-critical-loan-preflight-exact.yml`: a read-only source-local PR/dispatch job that checks out the **exact PR HEAD SHA**, confirms the checked-out tree is clean, captures `SOURCE_HEAD_SHA` and `SOURCE_TREE_SHA`, provisions GraalVM/JDK 25, runs `mvn -B -ntp -Dtest=ActorCriticalLoanPreflightTest -DfailIfNoTests=true test`, and fails if JUnit XML is absent, reports fewer than nine executed tests, or has any skipped/failed/error test. It reports `NATIVE_PREFLIGHT_JUNIT_RESULT` only after all checks pass. **It does not run a native protected-state guest.**

Author-side exact committed blob structural review: **17/17 assertions passed**, including all four-argument recursive calls, fail-closed AST boundaries, test method presence, exact PR-head checkout and JUnit criteria. The same XML-counter predicate was exercised on **8/8 local Node 22 synthetic fixtures**, including missing counters, overflowed counters, failures, errors, skips and insufficient test count. Neither result proves Maven compilation. At the time of this note, the latest branch commit had **no registered exact-head Actions runs**.

### Cross-org carrier restriction

The repository is private. The outstanding [conformance security incident #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22) explicitly prohibits private-source exports pending independent security review, containment and destination authorization. A temporary **private** branch in another GitHub organization is still an export of private source with durable Git history/artifact-retention implications. No cross-organization source mirror, CI carrier push, or privileged remote checkout was performed in this work. A permissioned source-local/owner-controlled runner is the preferred execution route.

These gates remain **DRAFT / NOT EXECUTED** until source-local GitHub Actions admission is fixed and complete JUnit XML + compiler logs are available.

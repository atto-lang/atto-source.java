# Real three-way reconciliation: serial execution tickets and typed value-match

Status: **draft integration**; target #656, source branch #661.

Pinned source heads:
- Actor continuation/ticket branch: `7014f45f0013c28b85b6e4702c62c7a1dc540d0e`
- Typed value-match branch: `c6bfb96d7602b4d7a866b2516c3bf87dee7b87bb`
- Actual common ancestor: `4b0ff7dd4063df2906dc2fed0f6b6348b69807c6`

## Executed merge audit

Private, SHA-pinned funded [three-way merge probe 38196379589](https://github.com/zed-pkg-test/java-app/actions/runs/38196379589) staged the **exact** ten independently modified Java files and their exact ten common-ancestor versions.

`git merge-file` cleanly merged:
- `Ast.java`, `OresEvalRootNode.java`
- `ActorRuntime.java`, `CapabilityChecker.java`, `RuntimeGarbageCollector.java`
- `OwnershipChecker.java`, `TypeChecker.java`

Three files required **eight semantic conflict resolutions**:
- `Lexer.java`: preserve actor `critical/using/serial` and typed-effect `suspends` keywords.
- `Token.java`: preserve actor admission tokens and typed-effect `SUSPENDS/THROW/RAISE/PANIC/RECOVER`.
- `Parser.java`: merge modifier guard, serial `parseActorClass(..., boolean serial)` with `consumeDeclaredName`, both `suspends` and `serial` modifier fields/constructor, critical-lambda constructor, and lexical `valueArmDepth` try/finally restoration.

The funded probe now resolves **only** those eight audited conflict shapes, refuses unexpected conflicts or missing source SHA verification, and attempts GraalVM Maven verification with the typed-match runtime and focused regression tests. Its output does not overwrite either original PR.

## Security/release obligations

A successful textual merge is not a proof of correct execution-context semantics. A final integrated runtime must additionally prove:
1. `ARM_RESULT` stays owned by its originating task and lexical match-frame identity across `await`.
2. Every normal completion runs registered `defer/finally` and releases scoped resources **before** publishing.
3. Canceling a pending actor task does not silently report that its guest cleanup ran. Cleanup-only authorization must bind actor/incarnation/task/receive and be mutually exclusive with normal user work.
4. Graceful stop waits for legally runnable cleanup; noncooperative/forced kill cannot promise guest cleanup and must surface an explicit cleanup-incomplete failure.
5. Stale/duplicate callbacks cannot enter a replacement actor incarnation, borrow another serial handler, consume invalid CONTROL capacity, or publish twice.
6. Exact combined-tree JVM, GraalVM AOT/hybrid, formal safety/liveness models and LLVM conformance/fail-closed gates must pass before merging.

No merge of #656 or #661 is authorized by this document; keep both draft until the combined source and cancellation behavior are proven.

# Java/GraalVM `define macro` current-main integration audit

**Status: DRAFT / SOURCE PORT ONLY / JUNIT AND NATIVE IMAGE NOT EXECUTED.**

## Source provenance and scope

- Source: [Java macro PR #2049](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2049), pinned original head `681ad0d6cd8f6af51e7e96cd985b087d7dfb107b`.
- Target: a **new integration branch based on current Java `main`**, not a force-merge or reset of the divergent macro PR.
- The original PR's **41 changed paths** are all represented in this new branch.
- Existing .java/.atto naming compatibility and mainline parser/runtime ownership policy are not intentionally removed.
- New shared test vectors: all **seven** byte-identical `.atto` files from [common-source PR #218](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/218), stored at `src/test/resources/macro-scalar-aot-v1/`, with its manifest.
- Extra JUnit checks: `MacroSharedScalarAotVectorsTest`, `MacroSharedScalarBudgetsTest` and `MacroProtectedStateIsolationTest`.

## Manually resolved integration points

1. `Ast.Decl` now permits macro declarations/invocations **and** the newer `StateContainerDecl` and `VirtualActorDecl`; their constructor/binding definitions remain from current main.
2. Both file-root and module `define macro` parser branches are inserted **before**, without removing, current-main `isProtectedDeclarationHead()` checks and sealed-state parse gates.
3. `OresCompiler.parseAndTypeCheck` attaches macro source identity, retaining current-main `compileProtectedScalarI64` and its independent MIR admission.
4. `MacroExpander.runtimeDeclarationName` reserves protected-state and virtual-actor names during generated-declaration collision checks; opaque protected declarations remain intact and never become compiler-authored macro body code.
5. All other original PR modified-file hunks were applied only when their old context had exactly one match in current main. No wholesale old-file overwrite.

## Executable release gates — still open

1. Exact SHA/tree provenance, complete Java 21+ Maven compilation and full JUnit verification, including existing source/runtime tests.
2. Execute the seven source-identical vectors through actual compiler semantic admission. The three positives must yield guest integer **42**; all four negative cases must reject before executable bytecode generation. Front-end JUnit values do **not** equal native execution evidence.
3. Actual program-specific GraalVM Native Image AOT publish and guest execution; capture binary hash, source bytes, exact JDK/Graal version, steps and logs. Do not substitute a synthetic `return 42` native stub.
4. Repeat protection-specific tests to ensure macro output cannot bypass sealed actor state, checked ownership, capabilities or effect admission.
5. The original Java profile mismatch has been corrected in this branch: `MacroExpander.MAX_DECLARATIONS = 8` per translation unit and `MAX_EXPANSIONS = 128` now align with LLVM/.NET stage-0. Dedicated JUnit checks cover 8/9 declarations, 128/129 expansions, 511/1023 closed const AST nodes, and inclusive depth 64/65. The compiler-specific tests are authored, **not yet executed**, and broader Java macro syntax/hygiene still exceeds LLVM's supported stage-0 subset.
6. Current-main integration review, CI, negative artifacts and independent approval before converting any macro PR from draft or merging.

Private source **must not** be transferred to a cross-organization CI carrier without an independently approved exact destination and compliant protected-source admission. Current source-org GitHub Actions jobs have repeatedly returned `steps=null`; they are **NOT_EXECUTED** and establish neither compiler success nor failure.

**Do not merge or publish this branch based on patch application or file-identity checks alone.**

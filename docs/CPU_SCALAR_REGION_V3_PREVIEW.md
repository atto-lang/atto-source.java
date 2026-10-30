# CPU synchronous scalar regions — stage-v3 preview (not release parity)

## Deliberately narrow positive source path

This Java/Graal **draft branch only** admits scalar synchronous CPU expressions:

```ores
pub fnc main(): int {
    return cpu { return 40 + 2; };
}
```

- `cpu { 42 }` and `cpu { return 42; }` return a plain **int**, never `Future<int>`.
- Only closed signed-i64 integer literals, unary +/-, binary +, -, * are admitted.
- Before expression parsing, the Java parser caps a scalar region at **1,024 tokens**, **64 parenthesis levels** and **64 consecutive unary-operator tokens**, rejecting nested blocks. After parsing, the scalar AST constructor independently caps accepted expressions at **512 total nodes** and **64 levels** from the root. Both limits are rejection gates; they are not source-runtime performance certifications.
- Arithmetic uses `Math.addExact`, `Math.subtractExact`, `Math.multiplyExact` and `Math.negateExact`: overflows throw, never wrap.
- The type checker resolves the dedicated `Ast.CpuScalarRegionExpr` as `int`. The ownership checker visits it explicitly. A closed AST constructor denies name references, actor state, call/interop, `await`, `rt` effects, assignment, buffers, GPU nested execution, and arbitrary statements. Interpreter only executes the checked scalar subset and throws on unrecognized opcodes.
- `cpu task { ... }` retains its existing asynchronous `Future<T>` submission / sealed capture handling; `gpu task { ... }` and bare `gpu {}` stay fail-closed (no physical GPU provider, no implicit CPU fallback).
- Arbitrary `cpu {}` with bindings, multiple statements, channel I/O, data buffers, nested devices or suspensions is **still unsupported**. Do not silently run it on a general host evaluator.

## Compatibility and versioning

`contracts/v2/cpu-gpu-task-unsupported-v2.json` is historical evidence that synchronous `cpu {}` failed on an earlier compiler. It must **not** be edited retroactively. A future v3 corpus must explicitly distinguish the admitted closed-scalar subset from unsupported full region forms across Java, LLVM, .NET, Wasm and Forge.

This branch deliberately does not claim v2 five-backend parity or production `cpu {}` semantics. It is a separate draft PR stacked on Java domain-hardening #2267, to be rebased only after v3 conformance approval.

## Release-blocking proof

1. Run `CpuScalarSyncV3Test`, `CpuGpuTaskV2ContractTest`, `ExecutionDomainAdmissionTest` and full JVM Maven/Graal guest suite at **this exact head**, with executed job steps.
2. Verify source-to-AST/type checker/ownership-evaluator and JDK 25 compile exhaustiveness, formatter and checked-arithmetic diagnostics; a correctly-shaped AST alone is not execution evidence.
3. Add a versioned v3 normative corpus with parallel negative and positive compiler tests on **all five backends**. Preserve cross-backend no-GPU-fallback and no-artifact negative gates.
4. Integrate typed buffer ownership and lexical borrowing, signed overflow parity, actor-safe nonparking continuations, sync `void`, nested host/device transfer fences, and physical device proof in later milestones.
5. No merge before independent review. No private source export to an alternate runner without approved destination, retention, and tree/blob attestation.

Latest draft verification (2026-10-29): AST/node/lexical limits, injected-AST rejection, optimizer-preservation and positive guest fixtures are committed but **not yet compiler-executed**. Exact-head source-org Actions still failed with no job steps (runner not assigned). The local environment has only OpenJDK 21 and no Maven; this does not qualify as the project's JDK 25/GraalVM build. The private cross-org mirror was previously blocked at commit creation, and no mirror-run success is claimed.

The standalone CPU/GPU reference graph does not substitute for source compiler execution.

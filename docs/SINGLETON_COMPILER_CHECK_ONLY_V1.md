# AttoLang singleton: Java compiler check-only stage 0

Status: **draft, NOT_EXECUTED, NOT_CERTIFIED**. This stage does not ship an executable singleton.

## Resolution of competing Java semantics

Java PR #160 proposed mutable **memory-slot** singleton modules; Java PR #665
implemented a deeply immutable **process-wide** registry on an integration branch
and separately identified the Graal native-isolate boundary. They do not establish
one consistent current-main guest contract. Do **not** silently conflate them.

For source-level `define singleton`, this stage targets:
- an immutable application-domain snapshot associated with compiler-owned
  (namespace, module, declaration) identity;
- once-only completed publication before any other actor can observe it;
- no actor-private/mutable authority, prepublication receiver escape, or
  untrusted-actor capability transport;
- no claim of process-global identity across independent Graal native isolates
  without a trusted supervisor coordinator and explicit authenticated transport.

The historical mutable per-slot registry remains a distinct **internal runtime
primitive**, not a second definition of the `singleton` source keyword.

## Syntax / current check-only subset

```ores
define pub singleton Settings as
    const int answer = 42;
    const bool ready = true;
    const string label = "safe";
end
```

- `OresCompiler.checkSingletonDeclarationsOnly(source)` builds a typed
  `Ast.SingletonDecl` through the production lexer/parser in explicit check-only
  mode, with field source locations.
- Root/module declarations and `define pub`/`pub define` are admitted.
- Only at most 64 literal-initialized, explicitly typed, immutable primitive
  `const` fields are currently conservatively checked.
- `ORES-SINGLETON-SELF-ESCAPE` rejects references to `self` or the declared
  singleton while initializing. `ORES-SINGLETON-MUTABLE-ALIAS` rejects mutable
  bindings and any type whose transitive immutability is not established.
- Dynamic initializers, methods, imports, closures/captures, generic aliases,
  complex allocation, and mixed check-only declarations are rejected. This is
  a **soundly narrow** subset, NOT a claim of full type/effect/borrow checker
  integration or deep-immutability analysis for arbitrary types.
- Normal `Parser.parse`, `OresCompiler.parseAndTypeCheck`, and manual
  `TypeChecker` admission still reject singleton execution using
  `ORES-SINGLETON-UNSUPPORTED`.
- Contextual identifier `singleton()` remains legal outside declaration
  position. No new global `singleton` reserved token is introduced.

## Verification commands / limits

```sh
mvn -B -ntp -Dtest=SingletonCheckOnlyTest test
mvn -B -ntp verify
```

Neither command has been executed or evidenced at this PR head. Source-org
zero-step CI is **NOT_EXECUTED**; no automatic fallback into another private
organization is authorized by this change. Record the exact source SHA/tree,
runner/job/run IDs and stepful result before promoting a check-only pass.

## Required follow-up before runtime enablement

1. Reconcile parser/class/module identity with imported aliases and linker
   resolution; integrate the real Java type/effect/ownership checker, including
   nested values, generics, closures, and use-after-transfer.
2. Implement a bounded supervisor-owned publication registry: once-only
   publication, sticky initializer failure, cycle detection, cancellation/stop
   fencing, teardown, hot reload and domain-aware identity.
3. Compile one real guest source fixture and execute concurrent singleton reads
   across trusted actors; separately test Java Native Image and native-isolate
   behavior. No JNI/static-global shortcut may impersonate cross-isolate identity.
4. Execute all frozen 20 singleton/shared-state conformance cases for the
   normative five backends, including sanitizers, NativeAOT, and Wasm workers,
   then capability-aware checks for Cranelift/JS/Rust/HDL.
5. Require exact-head nonzero-step evidence and independent security review.

Tracking: conformance #650/#651, Java #2343/#2316, LLVM #1203,
.NET #267, Wasm #191, Forge #146/#159.

## Additional compiler gate hardening — 2026-11-11

`SingletonCheckOnly.check` now validates field name uniqueness and the
64-field budget **after AST construction**, not only during parsing. This
rejects forged/rewritten ASTs and future transformation passes that might
bypass syntax-time checks. Explicit `Double.NaN` and nonfinite double
values cannot enter a singleton's primitive immutable literal proof.
Two JUnit regression methods exercise fabricated AST declarations directly,
including positive/negative infinity and NaN.

There are now **12 authored JUnit test methods** in
`SingletonCheckOnlyTest`. They have **not** been confirmed to execute at
the current branch head. Normal `Parser.parse`, `TypeChecker`,
`OwnershipChecker`, and `OresEvalRootNode` remain fail-closed for runtime
singleton activation. This code is **not** Java guest lowering, AOT, once-only
runtime publication, a cross-isolate registry or five-backend parity evidence.
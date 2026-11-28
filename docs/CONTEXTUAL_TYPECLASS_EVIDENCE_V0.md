# AttoLang contextual typeclass evidence: AOT call-site contract (v0)

**Status:** compiler-library planner implemented in `ContextualEvidencePlanner`;
**source syntax, AST, ownership checker, interpreter, LLVM/.NET/Wasm/Forge
lowering and cross-backend guest tests are not yet implemented.**
Do not report these APIs as executable AttoLang `.atto` source.

## Decision

Adopt only the **contextual typeclass evidence** portion of Scala 3 implicits.
Do **not** introduce general implicit conversions, implicit method selection,
runtime instance discovery, ad-hoc fallback, priority, or overlapping
instances. This feature must be semantically identical under AOT and JIT.

A typeclass is a compile-time declaration identity. An instance binds a
unique, globally coherent static dictionary symbol to one concrete or generic
type head. All imported instances must be included in the coherence check
*before* call-site planning; import order is never a precedence rule.

## Proposed AttoLang syntax (NOT yet accepted by parser)

```atto
namespace demo;

define trait Show<T> as
    show(T value): string;
end

// Proposed explicit instance declaration, not a runtime object.
define given Show<int> as
    show(int value): string {
        return value.to_string();
    }
end

// The contextual parameter is not part of source-visible call arity.
fnc display<T>(T value, using Show<T> printer): void {
    println(printer.show(value));
}

fnc main(): void {
    display<int>(42);
}
```

The syntax is **illustrative**. Existing `trait` declarations currently
share interface AST representation; neither `given` nor a contextual `using`
function parameter can safely be advertised as supported. `using
request.borrow() as view { ... }` already has a distinct scoped-loan
meaning and must retain that meaning.

## Current compiler-library proof

`ContextualEvidencePlanner.plan(registry, requirements, explicitSymbols)`
takes a pre-registered coherent `TypeclassRegistry.Registry`, an ordered list
of fully instantiated contextual parameter requirements, and optional
explicit dictionary references keyed by parameter slot.

It returns **immutable compile-time metadata**:
- The declaration-order parameter slots and their exact typeclass identity
- Ground generic type arguments, static dictionary symbols, and substitutions
- Whether an exact registered witness was explicitly named

No dictionary object is created. No guest function is called. The plan does
not silently downcast, convert, box, allocate, or infer a missing instance.
An explicit symbol must be the registered witness for precisely that goal:
this is not a runtime injection or actor/supervisor authority channel.
Unknown or duplicated slots, wrong arity, unbound generics, missing instances,
or a mismatched explicit witness are hard compilation errors. A bounded
input budget prevents unbounded search over maliciously deep type graphs.

Callers must run ordinary generic inference and defaults **before** this
planner, using the shared `GenericCallArguments` rules; the planner must not
guess a type to find convenient evidence. No part of this compiler-library
result is a granted effect, lifetime, mutable borrow, FFI capability, actor
state handle, or permission to cross an isolation boundary.

## Integration gates before enabling source syntax

1. Add a dedicated typeclass/instance AST (do not silently reinterpret
   ordinary interface/contract declarations). Define `given` contextually
   without breaking existing identifiers or `using` borrow scopes.
2. Gather declarations and imported instances across all source files and
   dependency boundaries, validate ownership/orphans and overlapping heads
   with fully qualified, versioned identities, then freeze the registry.
3. Add contextual parameter metadata to signatures and overload selection.
   Preserve the existing source-visible arity; compiler-generated dictionary
   slots must not allow an overload collision or callable escape.
4. After type inference, type-check explicit witness references and elaborate
   contextual calls into statically bound evidence. Include substitutions in
   monomorphization/cache/linker identities, verify imported signatures, and
   reject unresolved/dynamic evidence in strict AOT mode.
5. Verify evidence cannot carry live mutable borrows, cross actor isolation
   boundaries, acquire capabilities, or bypass `pure`/effect checks. All
   evidence method calls are checked as ordinary calls.
6. Add `.atto` positive and negative source cases and execute compiler-backed
   GraalVM, LLVM O0/O2, .NET NativeAOT, Wasm and Forge tests. Establish exact
   PR-head/tree CI receipts with nonzero runner steps, then request independent
   review before merging.

## Current verification scope

`ContextualEvidencePlannerTest` contains eight JUnit contracts for ordered
static evidence, generic substitutions, exact explicit symbols, no conversions,
unbound/duplicate slots, repeated generic heads, qualified identity, bounded
search, and coherence/orphan preservation.

These are **compiler-library unit tests only**. They do not prove that the
illustrative `.atto` program parses, type-checks, executes, or produces native
code. Record actual executed command/runner results separately.

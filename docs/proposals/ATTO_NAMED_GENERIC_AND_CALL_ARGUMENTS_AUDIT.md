# AttoLang: named type parameters and named value-call arguments

Status: **audit + staged implementation; not backend parity certification**.
This document covers the current Java `main` contract and the guarded
integration work in Java PR #2086. A parser accepting source is not evidence
that a compiler, ownership pass, AOT image, or native guest can execute it.

## Two distinct channels

```atto
fnc choose<foo, bar=string>(foo first, bar second): foo {
  return first;
}

// Named compile-time TYPE arguments use =; call types bind to declaration slots.
choose<foo=int, bar=string>(42, "hello");
choose<bar=string, foo=int>(42, "hello");
choose<int, bar=string>(42, "hello");

// Named runtime VALUE arguments use : and require a checked call plan.
choose<foo=int, bar=string>(second: "hello", first: 42);
```

The first three calls exercise the Java generic-call binder. The final call
is a **target contract**, not enabled on Java `main`: Java PR #2086 has a
draft implementation of part of this syntax. Do not substitute `=` for
`:` or reinterpret ordinary assignment as a call label.

Declaration `fnc choose<foo=string>()` means a default **type** for the
generic declaration slot. Call `choose<foo=string>()` means an explicit
named **type argument**. Other contexts such as `Type<messages=Message>`
can be fixed compile-time metadata, not a declaration default: the owning
type must determine the interpretation.

## Generic binding and error contract

1. Preserve both source type-argument order and declaration slot order;
   neither may be reconstructed from a hash-map iteration order.
2. Bind positional type arguments from the left, then bind named arguments
   by exact case-sensitive declaration name. A positional argument after a
   named one is an error.
3. Reject unknown names, duplicates, a positional/named collision, excessive
   arguments, and explicit `<...>` on nongeneric callables.
4. Explicit concrete types are immutable bindings; inference may fill only
   genuinely unbound slots. `_` is an inference demand, **never** a default;
   a nested `_` is invalid.
5. Apply declaration defaults only after successful inference, in
   declaration order, with earlier resolved parameters substituted.
   Reject missing required generics and invalid/dependent defaults before
   lowering. Unsupported `Any`/`Unknown` defaults remain fail-closed.
6. Reject `_` as a declaration name in every declaration family (function,
   routine, method, interface, actor, class, alias and forward declarations).
   Generic method/class declaration defaults must not be silently admitted
   until those AST and lowering paths retain structured default metadata.
7. Canonicalize the ordered resolved generic tuple for specialization and
   exported ABI identity. A public name/default change affects source API
   compatibility; imported users must be invalidated as necessary.
8. No implicit coercion, type reflection, dynamic specialization, or
   production backend promotion from merely accepting a syntax tree.

## Named value-call binding and execution

A checked plan must bind by the **actual statically resolved callable**
declaration; parameter names are not overload selectors. A call with labels
cannot select a different overload by types or names.

- Select the callable using the existing name/owner and value arity rules
  (including optional slots), then bind source items to formal slots.
- A positional prefix may precede the named suffix. No positional/spread
  argument may follow the first named argument.
- Reject duplicate, case-mismatched, unknown, extra or missing required
  slots, including collisions caused by tuple/record projections.
- `T? name` is omittable and callee-visible as `Option<T>`; omission
  does not evaluate an expression or shift subsequent formal slots.
  `Option<T> name` remains a required supplied argument.
- Evaluate the receiver once, then every supplied expression exactly once
  **in lexical source order**, even when its formal slot order differs.
  Suspend/cancel/cleanup and borrow/move tracking follow the same order.
- After type, ownership and effect validation, permute evaluated temporary
  values into declaration ABI order. No guest-visible dictionary lookup.
- Retain a checked plan bound to the exact AST/callable identity. Replacing
  a callee, explicit generic, receiver, argument expression, label,
  projection, optional-presence bit or call origin invalidates the proof.
  Recheck after a compiler rewrite; do not copy checked authority merely
  because labels or structurally equal expression trees match.
- Overrides and interface implementations preserve caller-visible label
  identities at the same formal ordinal. Rename is an API change.
- Function values without compiler-authenticated parameter names, dynamic
  calls, actor messages and unsupported native intrinsics reject named
  arguments rather than inventing labels. No closure/function transport
  across actor channels or pub-sub.

## Required positive fixtures (each executable path must provide receipts)

| ID | Fixture | Observable assertion |
| --- | --- | --- |
| G01 | `f<foo=int,bar=string>()` and reversed named order | Same resolved specialization |
| G02 | `f<int,bar=string>()` | Positional prefix and named suffix |
| G03 | `f<foo=_>(42)` | Inference succeeds from a value |
| G04 | `f<T=int,U=T>()` when T is caller-scoped | Correct lexical type reference |
| G05 | Dependent declaration defaults `<A,B=A,C=B>` | Stable final canonical tuple |
| G06 | Nested concrete types `Array<Option<int>>` | Exact type and ABI substitution |
| G07 | Generic direct `fnc` and direct-only `routine` | Same type binder, distinct reifiability |
| G08 | Generic method with named call type arguments | Receives canonical specialization |
| V01 | `f(b: effectB(),a: effectA())` | `BA` effects, formal `a,b` ABI |
| V02 | `f(1,c: 3,b: 2)` | Positional prefix and name permutation |
| V03 | `f(optional: Some(2),required: 1)` | Omitted slots do not shift |
| V04 | Module-qualified, instance, static and inherited methods | Correct exact selected callable |
| V05 | Function vs routine calls, anonymous trailing closure | No extra callback invocation |
| V06 | Tuple/closed record spreads admitted by static proof | Once-only projection/evaluation |
| V07 | `suspends` callee and suspending arguments | Resume once, preserve lexical effects |
| V08 | Borrow/move/actor ownership and cancellation | Same loans as positional call |
| V09 | AOT/JIT and optimized/unoptimized execution | Identical stdout and diagnostics |

## Required negative fixtures

| ID | Reject | Reason |
| --- | --- | --- |
| N01 | `f<foo=int,foo=string>()` | Duplicate generic binding |
| N02 | `f<int,foo=string>()` | Positional/named collision |
| N03 | `f<foo=int,string>()` | Positional after named |
| N04 | `f<typo=int>()` or case mismatch | Unknown generic name |
| N05 | `f<_>()` with no inference source | Hole cannot use default |
| N06 | `f<Array<_>>()` | Nested inference hole |
| N07 | `fnc f<_>()`, `class Box<_>` | Reserved declaration slot |
| N08 | `fnc f<A=B,B=int>()` | Forward default |
| N09 | Unsupported `Box<foo=int>` type-position named arguments | No declaration-aware binder |
| N10 | `f(b: 2,b: 3)` | Duplicate runtime label |
| N11 | `f(a: 1,2)` or spread after label | Invalid source order |
| N12 | `f(1,a: 2)` | Slot collision |
| N13 | `f(unknown: 1)` | Unknown runtime label |
| N14 | Missing required / surplus named argument | Invalid supplied-slot set |
| N15 | Ambiguous same-name/same-arity declarations varying only by labels | Labels not overload selectors |
| N16 | Inherited override silently renames a formal | Source-incompatible contract |
| N17 | Rewritten call gets copied proof with altered value/callee | Identity-bound proof bypass |
| N18 | Named args on opaque/dynamic/intrinsic callables | No authenticated signature |
| N19 | Record spread from Map, open record view or move-only fields | Invalid field-pack provenance |
| N20 | Capability-bearing/moved value hidden in named argument | Ownership/capability bypass |
| N21 | Unresolved default overridden at every call site | Bad declaration still rejected |
| N22 | `f(a=1)` interpreted as `f(a:1)` | Syntax conflation |
| N23 | `f<foo=int>()` where `f` is nongeneric | Excess type-argument marker |
| N24 | Parser comparison `a < b && b > (c)` mistaken for generic call | Grammar disambiguation |

## Verification / promotion

Exact source SHA and tree, supported feature, compiler/guest command, test
count, zero skipped relevant tests, exit/stdout, negative diagnostics, and
nonempty CI job steps must be recorded separately for Java/JVM, GraalVM AOT,
LLVM/Clang, Wasm, .NET/CIL/NativeAOT and Forge/Cranelift. A host-only binder
test is not a compiled AttoLang guest test. Existing `.ores` fixtures remain
compatibility inputs; new normative language fixtures should use `.atto`.

A CI job with `steps=[]` or `steps=null` is **NOT_EXECUTED**, not a
compiler failure or a pass. Do not mirror private source to an alternate
organization without the required review/authorization and exact-tree
integrity controls. Do not merge or claim parity based on authored fixtures.

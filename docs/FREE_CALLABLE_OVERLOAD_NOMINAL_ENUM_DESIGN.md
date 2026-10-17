# Free `fnc` / `routine` overloads and nominal enum dispatch (pre-1.0)

> **Later target amendment (2026-10-17):** [ENUM_VARIANT_OVERLOAD_EXTENSION.md](ENUM_VARIANT_OVERLOAD_EXTENSION.md) adds safe, static `Foo.A` case-type overload specialization. Statements below forbidding case-specific overload keys apply only to the earlier enum-family phase; do not infer that either phase is executable in the current runtime.

Status: **name+arity exists in Java and the LLVM native free-function subset**.
The **same-arity extension permits only distinct eligible nominal enum
identities**; primitive-type differences are not overload discriminators.
The canonical specification is
[NOMINAL_ENUM_OVERLOAD_CONTRACT.md](NOMINAL_ENUM_OVERLOAD_CONTRACT.md).
Its selection rule supersedes older primitive-rank proposals. This staged
feature is not generally executable; class vtables, imports and LLVM native
guest code still require parity. Do not use illustrative examples as evidence
of compiler acceptance.

## Current closed-world rule

For file/root and module free callables, the dispatch key is
`(declaration scope, callable name, visible value-argument arity)`.
Each root file needs a unique compilation-unit scope at the linker; each
named module needs its own stable qualified identity.

- `fnc f()`, `routine f(int)`, and `fnc f(int, int)` may coexist in the
  same file/module and resolve by arity.
- `fnc f(int)` and `routine f(bool)` collide. FNC vs ROUTINE is **not**
  a second overload namespace; `routine` is direct-call-only and `fnc`
  may be reified when it has an unambiguous callable context.
- Parameter names, parameter/return types, generic count, annotations,
  async/effects, and visibility never distinguish existing arity slots.
- Class/trait/interface instance and static method slots retain their
  existing **arity-only** contract; this document does not change virtual
  method dispatch or override identity.
- A module's field/binding/class and callable cannot occupy the same value
  name. Separate modules with the same callable name must be qualified.
- Calls and fixed spreads select by expanded argument arity before normal
  type, mutability, ownership, effect, and visibility validation.
- The `main` ABI entrypoint may not be overloaded where a native exported
  symbol contract reserves it; guest entrypoints must be closed-world.

## Normative future extension: disjoint nominal enum IDs

Allow **same-arity** free-callable overloads only when each declaration pair
differs at a corresponding parameter position by two distinct, eligible
**named enum declaration IDs**. Other parameter positions must have the same
canonical admitted type ID. Primitive type differences (including numeric
widening) cannot admit a same-arity pair. Never compare the structure of
structs, traits, interfaces, records, generic arguments or enum members to
select an overload.

Proposed example (enum syntax illustrative; not accepted by today's Java
declaration frontend):

```oreslang
define enum OfPrimitives {
    Foo = 1
}

define enum AlsoPrimitives {
    Foo = 1
}

pub fnc x(OfPrimitives value): void { }
pub fnc x(AlsoPrimitives value): void { }
```

`OfPrimitives.Foo` and `AlsoPrimitives.Foo` have different enum **type**
identities, even if both have numeric representation `1`. A numeric literal
`1` does not automatically become either enum type. A spelling like
`x(OfPrimitives.Foo f)` must not become a value-specific overload: selecting
by a particular enum member is pattern matching, not a function type key.

### Identity and collision rules

1. Assign each builtin primitive a compiler-defined immutable tag.
   A named enum's nominal ID is derived from its fully qualified declaration
   identity (package/compilation unit, module, declaration), not its member
   names, integer values, structural shape, or lexical alias spelling.
   Distinct enums are distinct even when their values are identical.
   A type alias for an enum keeps the **same** ID.
2. Evaluate each same-name+arity declaration pair: require at least one
   position where **both** parameter types are distinct eligible nominal enum
   declaration identities. All other positions must have identical canonical
   admitted identities. Otherwise reject the family at declaration or link
   time with an ambiguous-signature diagnostic. In particular,
   `f(Any)` vs `f(EnumA)`, `f<T>(T)` vs `f(EnumA)`,
   and `f(EnumA, Any)` vs `f(Any, EnumB)` are rejected, because arguments
   may satisfy both signatures. Never rank by specificity or fallback.
3. Resolve at the call site using the *static* argument types and the finite
   registry of allowed nominal tags. Apply named-argument binding and fixed
   spread expansion first; preserve **source-order, once-only** argument
   evaluation. If the static type is a union, erased generic, `Any`, or
   unknown and the overload is not uniquely determined, reject and ask for
   an explicit type narrowing; do not inspect runtime values.
4. Ordinary implicit numeric widening, enum-to-number coercion, or generic
   inference cannot influence which same-arity overload wins. An explicit
   conversion creates an expression with a new static type and can be used
   where the language provides such conversions.
5. The chosen declaration's full parameter contract is checked **after**
   tag-based selection: generic substitution, structural assignability,
   borrow/move capabilities, effects, visibility and arity still matter.
   Return types never select an overload.
6. Emit one deterministic linker/ABI identity for each declaration. Include
   a collision-free encoding of nominal enum declaration identity (or check
   any compact hash against complete metadata at link time). All compiler,
   JIT, Native Image, LLVM, wasm, imports/exports and ownership paths use
   one canonical selector serialization. Never rely on declaration order or
   auto-generated LLVM `.1` names.
7. Linked source files/modules must merge all visible exported overload slots
   and detect duplicates/overlaps at link time. Existing single-unit import
   checking is insufficient evidence of cross-file overload parity.

## Required gates before enabling nominal overloads

- Named enum parser/AST/typing + declaration-scoped nominal IDs in Java and LLVM.
- Unit checks for enum identity reuse, alias equivalence, equal enum values,
  same short name in different modules, collision resistance, and visibility.
- Positive native, Java JIT, Graal AOT and hybrid guest programs for
  distinct nominal-enum exact-ID dispatch; negative tests for primitive-only,
  mixed-primitive, overlapping generic,
  `Any`, structural, union, numeric-literal, and ambiguous imported families.
- Proof that ownership and effects are checked only against the chosen
  overload, and evaluation order remains lexical with named arguments.
- Stable AOT symbols and export/import manifests; binary/linker ABI
  compatibility and stale-cache invalidation tests.
- Class-method selectors remain arity-only until the same nominal-ID proof
  and exact-signature vtable dispatch are implemented and verified. The
  canonical language decision already includes instance and static methods;
  staged implementation does not imply an exception. No dynamic enum-value
  dispatch.

No performance or compilation parity claims are made for this future feature.

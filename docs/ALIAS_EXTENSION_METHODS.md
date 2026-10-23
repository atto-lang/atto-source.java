# Named-type extension methods — compiler-checked v1

Oreslang accepts this additional **declaration** syntax:

```oreslang
declare type Code = int; // identical semantics to define type Code = int;

define mtd double on Code(): int {
  return self + self;
}

fnc calculate(Code code): int {
  return code.double();
}
```

`mtd` and `on` are contextual words, not globally reserved identifiers.
An extension method is compiler-lowered to a module-local function with an
explicit first parameter named `self`:

```text
$mtd$Code$double(Code self): int
```

That internal name cannot be spelled as an ordinary user function. Calling
`code.double()` uses compiler-verified dispatch metadata attached to the
exact AST call site; at runtime it invokes the generated function with
`code` prepended to the ordinary arguments. It does **not** inspect the
erased Java `Long` runtime type to select a method.

## Identity, coherence and boundaries

- The receiver must be a **simple, existing, named type alias declared in the
  same module**. Primitive `int`, unresolved names, generic alias
  instantiations and arbitrary class/actor types are not extension receivers
  in this first version.
- The method is statically resolved for alias-typed lexical parameters and
  explicitly alias-typed local bindings, using the *identity of the checked
  lexical binding*. Shadowing the variable by a raw `int` does not inherit
  the alias's methods. Ordinary ints never inherit Code-specific methods.
- Lookup is by receiver alias, method name and explicit argument count.
  Repeating the same alias/name/arity is a compile-time collision.
- `self` is immutable by default and cannot be re-declared as an explicit
  method parameter. Mutable receiver extensions, generic extension methods,
  implicit newtype ownership and cross-module extension imports are not yet
  admitted. Extension methods with suspension and actor effects require their
  separate typed lowering and are not automatically assumed safe.
- Name, annotation, scope and ownership checks follow the existing compiled
  function contract. No object allocation is needed for int/bool aliases.

## Zorde and `with` validation integration

This declaration mechanism is a **behavior** layer, not a validation bypass.
For a future constrained type, e.g.

```oreslang
declare type Code = int with min 0 and max 5;
```

the compiler's normalized constraint IR should be the single source consumed
by Zorde and by Java/LLVM checkers. An untrusted `int` must not be treated as a
validated `Code` just because the underlying scalar ABI is the same.
Validation must run at construction, decoding or explicit checked-conversion
boundaries before an extension receives `Code`. A mutating extension must
re-establish invariants, and direct casts cannot silently bypass checks.

**Status:** This v1 implements extension grammar, Java static receiver lookup
and interpreter invocation for recognized named aliases. It does **not**
implement or certify Zorde's runtime API, the normalized `with` constraint IR,
validated nominal newtypes, independent LLVM extension lowering, or cross-module
import/coherence proofs. LLVM currently fails closed with
`EXTENSION_BACKEND_UNSUPPORTED`; tests must pass on both backends before parity
is claimed.

## Testing

`AliasExtensionMethodTest` covers source parsing, receiver isolation,
positive Java interpreter calls, typed-local lexical scope, arity overload
collisions and unsupported receivers. Until the source repository's GitHub
Actions runners execute steps, these are **written regression cases**, not
successful executed tests.

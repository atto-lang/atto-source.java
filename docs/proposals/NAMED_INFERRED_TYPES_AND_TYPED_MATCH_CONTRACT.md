# Named inferred types and typed match input/output (design contract)

**Status:** proposed specification and admission matrix; **not implemented on `main`**. No examples in this file should be treated as passing compiler/runtime tests. This contract must be integrated with the open expression-match / arm-local-return work (#296 and #404), not implemented as an incompatible competing match AST.

## 1. Surface syntax and exact inferred type

```ores
fnc accountId(): string {
  const T account = infer<T> struct {
    id: "acct_42",
    active: true
  };
  return match<T, string> account over
    on structural { id: string, active: bool } a -> {
      return a.id;
    }
    on _ -> { return "unknown"; }
  end;
}
```

`infer<T> struct` binds a fresh, lexical **compile-time type name** `T` to the type of the struct literal. The intended normalized type form is:

```ores
type T = struct {
  id: string,
  active: bool
};
```

**Never** normalize it to `T = structural { ... }`: the distinction is semantic. `struct` is a closed value type; `structural` is an explicit shape/view matching mode for pattern matching and parameter contracts, not a type-construction modifier. `type T = struct {...}` is a description of compiler-normalized type syntax, not evidence that the current `type` parser already accepts that spelling. Existing lowered `Record` metadata can represent the closed fields and permanent readonly capability while the language surface grows the explicit `struct` type spelling.

This named inference is **not** a declaration of a nominal class or fresh runtime identity. `T` is transparent to type compatibility and does not allocate a runtime `Type` object. The inferred type retains exact member types after literal widening, closed-field semantics, any nested qualifiers, and readonly/ownership constraints of `infer struct`. No new field writes or actor-crossing permissions are created by the alias.

The initial implementation should require the declaration name to match the inference name (`const T x = infer<T> ...`); mismatched names are an error. Restrict it to immutable `const` declarations while keeping the inferred struct permanently readonly. Names must be unique within their lexical type scope, may not silently shadow a generic parameter, built-in, imported type, or prior alias, and never escape that scope by implicit capture into module-wide declarations. Do not infer type names from runtime expressions, reflection, or untrusted actor metadata.

## 2. Named inference for tuples

```ores
fnc singleton(): Tuple[int] {
  const T account = infer<T> tuple ("foo", 5);
  return match<T, Tuple[int]> account over
    on structural (string, int) a -> {
      return tuple (a[1]);
    }
    on _ -> { return tuple (-1); }
  end;
}
```

The named compile-time type is:

```ores
type T = Tuple[string, int];
```

The **tuple is not a struct**. Preserve exact tuple arity and positional element types. `Tuple[int]` is the existing canonical tuple-type notation; the user-requested `Tuple(int)` may be admitted as type-position sugar only after ambiguity checks and parser/runtime parity. Do not quietly reinterpret a runtime call expression as a type. An unparenthesized `tuple (...)` remains explicit value construction; `infer<T>` marks type naming, not a second runtime tuple kind.

`on structural (string, int) a` is a proposed tuple **type-shape** pattern (not a tuple-of-two-value-patterns). Matching must test tuple kind, exact arity, and each slot type; bind `a` to the same readonly typed tuple; `a[1]` retains `int`. Extra slots do not match (unlike width-compatible record contracts). Preserve actor and ownership boundaries. If the parser cannot unambiguously distinguish the tuple type pattern, introduce dedicated `TupleTypePattern` AST nodes instead of coercing value patterns.

## 3. Typed match result contract

Two type parameters are ordered `match<Input, Output> subject over ... end`:

- `Input` constrains/checks the **scrutinee type**, evaluated once; it is not a cast. Incompatible actual input is a compile-time error.
- `Output` constrains the **result of every reachable arm**. A value-producing match has a result of exactly this declared type. Every completing arm must produce an assignable value, subject to exhaustiveness, control-flow, ownership, borrow, and capability checks.
- `match<Output>` retains the previously proposed **single-parameter result-only** form, to avoid reversing the contract from `TYPEOF_AND_TYPED_MATCH.md`.
- In value-producing `match` the `return value;` inside each arm returns **from the match**, not from the enclosing function, following draft #404. Use `return match<Input,Output> ... end;` to return from the function, or bind the match expression to a value.
- Bare `match ... over` is not a side-effect statement on the value-producing expression branch. Use `do match` for side effects, not a fake `void` output type. Do not merge an interim statement-only version that silently gives arm `return` a different target.

`Input` and `Output` are ordinary compile-time types, including inferred local aliases; neither is necessarily a *generic type variable*. Both require independent type resolution and lexical visibility. Do not reify erased generic types for runtime matching.

Canonical `on structural { ... } a` checks a **structural view** of a struct; it does not change the inferred `struct` type. Overlap, wildcard, and dead-arm proofs should use the input type and guard constraints. A fallback that becomes provably unreachable for a fully known exact inferred shape may be reported as redundant (warning or documented existing dead-arm rule); it must never be executed spuriously.

## 4. Implementation sequence

1. Extend the grammar/AST with `infer<IDENT>` for inferred struct and tuple literals, and represent the name solely in type-check metadata erased before runtime. Keep plain `infer struct` and `tuple (...)` backward compatible.
2. Add a lexical type namespace separate from value bindings; bind `T` only after the literal is statically inferred. Preserve `Record.readOnly` for struct types and finite positional `Tuple` types. Add user-facing `type T = struct {...};` spelling (or an explicit normalization layer) without confusing structural-pattern grammar.
3. Build `match<Input,Output>` on the existing draft expression-match machinery from #296/#404 with the canonical `over/on/end` grammar. Keep `match<Output>` result-only. Subject typing and output typing must both be checked; neither can mutate the other.
4. Add tuple type-shape patterns without disturbing existing tuple *value* patterns/destructuring. Ensure JVM/Truffle, JIT, AOT, and actor-isolate enforcement agree before advertising support.
5. Only merge once tests execute against the **exact source Git tree**. If Actions cannot allocate runners in the source org, mirror the exact tree to a funded private test repository, verify Git tree and blob SHAs, and run the existing suites there without cross-organization secrets.

## 5. Acceptance tests (must be automated before merge)

**Positive:** (1) exact requested `const T account = infer<T> struct` syntax, inferred `type T = struct` schema and field access; (2) `T` reused in a second typed binding within the same block; (3) nested lexical scopes and different names do not leak into parent/siblings; (4) exact `infer<T> tuple ("foo", 5)` syntax and type `Tuple[string,int]`; (5) tuple slot indexing `a[1]` retains `int`; (6) both `match<Output>` and `match<Input,Output>`; (7) arm-local `return` in expression match; (8) result returned to the enclosing function; (9) structural field/tuple patterns and guarded fallback semantics; (10) an inferred type used as input and a distinct alias used as output.

**Negative:** (1) duplicate, shadowing, or unknown type alias; (2) `const U x = infer<T> ...`; (3) type namespace confused with a runtime value; (4) field write through inferred readonly struct or matched view; (5) tuple wrong arity/slot types or out-of-range constant index; (6) `match<T, int>` arm returning string; (7) incompatible input subject vs declared `T`; (8) `match<void>` as value; (9) missing value on a completing arm; (10) escaping local alias to module signature; (11) cross-actor sharing of unsafe inferred references; (12) `infer<T>` applied to a side-effect expression rather than supported literal.

**Fuzz/property checks:** generating closed structs of distinct field permutations must preserve name-to-field associations; generated tuples must preserve exact arity/slot ordering; generated matches must typecheck only where both input and output contracts hold; no alias should affect runtime tree/object layout or create actor-shared mutable memory.

**Current status:** contract only; implementation and green JVM/native CI evidence pending.

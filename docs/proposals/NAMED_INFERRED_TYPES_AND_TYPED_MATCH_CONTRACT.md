# Named inferred types and typed match input/output (design contract)

**Status:** partially implemented on draft PR #495; **not implemented on `main`**. The parser/AST/type checker now contain an initial implementation of lexical `infer<T> struct` and `infer<T> tuple`, plus tests and a tuple constant-index typing fix. These code changes are **not yet test-verified**: source GitHub Actions jobs failed before runner allocation. `match<Input,Output>`, tuple structural type-pattern matching, and the user-facing `type T = struct {...};` parser remain unimplemented and depend on integrating expression matches from drafts #296 and #404. Do not advertise the full examples as executable.

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

The **tuple is not a struct**. Preserve exact tuple arity and positional element types. `Tuple[int]` is the current collection-type notation. The user-requested `Tuple(int)` **must also be accepted in type position** by the completed feature and canonicalized to `Tuple[int]`; it is not a runtime constructor or a function call. Keep type-position parsing separate from expression-position `Tuple(...)`/`tuple (...)`, and reject any ambiguity instead of interpreting a function call as a type. Do not quietly reinterpret a runtime call expression as a type. An unparenthesized `tuple (...)` remains explicit value construction; `infer<T>` marks type naming, not a second runtime tuple kind.

`on structural (string, int) a` is a proposed tuple **type-shape** pattern (not a tuple-of-two-value-patterns). Matching must test tuple kind, exact arity, and each slot type; bind `a` to the same readonly typed tuple; `a[1]` retains `int`. Extra slots do not match (unlike width-compatible record contracts). Preserve actor and ownership boundaries. If the parser cannot unambiguously distinguish the tuple type pattern, introduce dedicated `TupleTypePattern` AST nodes instead of coercing value patterns.

## 3. Typed match result contract

Two type parameters are ordered `match<Input, Output> subject over ... end`:

- `Input` constrains/checks the **scrutinee type**, evaluated once; it is not a cast. Incompatible actual input is a compile-time error.
- `Output` constrains the **result of every reachable arm**. A value-producing match has a result of exactly this declared type. Every completing arm must produce an assignable value, subject to exhaustiveness, control-flow, ownership, borrow, and capability checks.
- `match<Output>` retains the previously proposed **single-parameter result-only** form, to avoid reversing the contract from `TYPEOF_AND_TYPED_MATCH.md`.
- In value-producing `match` the `return value;` inside each arm returns **from the match**, not from the enclosing function, following draft #404. Use `return match<Input,Output> ... end;` to return from the function, or bind the match expression to a value.
- Untyped bare `match ... over` remains value-producing and must be consumed as specified by #404. To admit the originally requested **standalone** `match<Input,Output> subject over ... end` examples, explicitly typed two-argument matches MAY appear as expression-statements: calculate the result according to `Output`, discard it, and emit an unused-result diagnostic. `return` inside arms still targets the match, never the enclosing function. Prefer binding the result or `return match<...> ... end;` for production code. Use `do match` for intentionally effect-only code; do not use `match<void>`. This is an explicit extension to #404, which currently rejects bare match statements; implement it deliberately, not by retaining the legacy statement-return semantics.

`Input` and `Output` are ordinary compile-time types, including inferred local aliases; neither is necessarily a *generic type variable*. Both require independent type resolution and lexical visibility. Do not reify erased generic types for runtime matching.

Canonical `on structural { ... } a` checks a **structural view** of a struct; it does not change the inferred `struct` type. Overlap, wildcard, and dead-arm proofs should use the input type and guard constraints. A final wildcard fallback following a provably total structural pattern for the exact inferred `T` is **accepted with an optional redundancy warning** so the requested examples remain valid. A genuinely shadowed non-wildcard arm remains a hard error, as do overlapping patterns that invalidate established ordered-match rules. Never execute the fallback spuriously.

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

**Current status:** initial parser/AST/type-checker implementation and JUnit source are pushed in draft #495. No green JVM/native CI evidence exists for this head; source jobs have `steps=[]`. Value-producing typed matches, structural tuple type-patterns, normalized `type T = struct` surface parsing, and full cross-backend verification remain pending.

## 6. Audit and hardening notes (2026-10-08)

### Explicit distinction: implemented / specified

- **Pushed but not yet green:** the parser restricts `infer<T>` to direct immutable `const T name = ...` bindings with `struct` or `tuple` literal initializers. The AST stores `inferredTypeName` on the binding only, avoiding runtime type-variable objects. The type checker binds the inferred `Record` or `Tuple` type in a lexical namespace after the initializer passes type analysis. String-literal slots widen to `string`. Type aliases are resolved only in that block and descendant lexical scopes; mutable declarations, duplicate names, generic collisions, and several built-in/global-name conflicts are rejected.
- **Pushed but not yet green:** exact constant-index tuple selection yields its own element type and rejects positive out-of-bounds literal indices. Dynamic indices still require a safe joined type. Negative unary-literal and arithmetic constant-expression bounds proofs need separate tests.
- **Specified, not built:** compiling `type T = struct { ... };` directly as a source-level type alias; input/output typed match expressions; tuple type-shape patterns; type-position `Tuple(int)` sugar; accepted/redundant wildcard fallback; JIT/native runtime lowering.
- **Observed source CI:** GitHub Actions failed before test steps were allocated. This is neither a passing test nor a proven compilation error. Verify the exact source tree in a funded test org with a complete mirrored tree and then run both JDK 25 Maven and GraalVM/backend checks.

### Mandatory fail-closed invariants

1. No name binding until the full literal initializer type is known; no half-initialized alias, forward capture, or recursive self-reference through `T`.
2. The type name exists in the type namespace only; evaluating `T` as an expression must fail. Alias equality is transparent, but `Record.readOnly`, field types, and transitive borrow/actor sendability restrictions must never weaken.
3. Do not infer type names from arbitrary expressions or dynamic/reflection host objects. Reject spoofed runtime type metadata, opaque foreign values, and erased generic matches instead of pretending `T` is reifiable.
4. An inferred type name must not collide with lexical generic parameters, local aliases (including parent scopes), nominal types, imported aliases, or built-ins. Check imported qualified namespaces before promotion from draft.
5. Tuple patterns are distinct from tuple value/destructuring patterns. Preserve exact length, positional slot types, and immutable views. Structural record matching may be width-compatible; tuple matching must not be width-compatible.
6. Match scrutinee evaluates exactly once; inferred type parameters are compile-time checks, **not casts** or mechanisms for laundering actor ownership. Each completed arm returns an `Output`-assignable value; missing returns and improperly escaping borrows are hard errors.
7. Typed standalone matches never turn `return` into a function-level return or silently skip output type-checking. Empty or void results must follow explicit `do match` semantics.
8. The compiler must not emit a runtime class or special heap object for type aliases. Static type-erasure must preserve guest value ABI and actor-isolation boundaries.

### Additional admission cases

- Positive: `match<T,Tuple(int)>` and `match<T,Tuple[int]>` agree in **type position**; named tuple strings widen from singleton literals while constant index `a[1]` remains `int`.
- Positive: the user's standalone two-argument typed match parses, typechecks, and runs with unused-result warning; `return match<T,U> ... end;` returns to the containing function.
- Negative: attempted runtime expression `T`, generic `T` shadowing, alias escape outside lexical scope, double evaluation of match scrutinee, deliberately unreachable non-wildcard arms, missing arm results, unsafe ownership capture, and runtime `Tuple(int)` type-position confusion.
- Property check: random records with permuted declaration order retain field names/types; tuple indices and type argument arity preserve exact ordering; no program changes heap allocation/actor identity just because a type alias is named.

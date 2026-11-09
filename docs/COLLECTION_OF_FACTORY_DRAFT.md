# Collection Type.of(...) — Java first implementation slice (draft)

Status: **SOURCE AUTHORED; exact-head JUnit/Truffle, AOT and backend parity NOT EXECUTED/NOT CERTIFIED**.

This branch is stacked on the transactional `~map` Map guest work in
[Java PR #2732](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2732),
which is itself stacked on typed-empty Map and readonly record/tuple work.
Do not merge this child independently or present authored tests as execution proof.

## Lowering that this slice adds

| Source | Parser AST | Existing semantic/runtime gate |
| --- | --- | --- |
| `Map.of<K,V>(k => v, ...)` | `MapLiteralExpr(Map<K,V>, entries)` | Existing scalar-only typed-Map TypeChecker, OwnershipChecker, transactional `OresKeyedMap.putAll` |
| `Map.of<K,V>()` | `TypedEmptyAggregateExpr(Map<K,V>)` | Existing owned typed-empty Map gate |
| `Map.of(k => v)` | Contextual MapLiteralExpr | Allowed only as direct `Map<K,V>` binding initializer |
| `Map.of()` | ContextualEmptyMapExpr | Allowed only as direct `Map<K,V>` binding initializer |
| `Array.of(values...)` and `~array(values...)` | `ListExpr` | Pre-existing array literal path and inferred element types |
| `Tuple.of(values...)` | `TupleExpr` | Pre-existing finite positional tuple path |
| `Record.of(name: value, ...)` | readonly `ObjectExpr` | Pre-existing `~record` shape and readonly checks |

Factories with explicit generic type arguments other than Map fail closed
in this slice. `Map.of(...source)` and untyped Map expressions fail closed.
Never accept `Map.of` as a Java host `Map.of` call, insert host-dynamic
dictionaries, or bypass ownership/actor boundary checks. Map entry evaluation
and commit use the parent PR's staged all-or-nothing map semantics; guest
expression side effects themselves are not rolled back.

**Important:** this is not yet `List.of`, `Vector.of`, `Set.of`,
`Entry.of`, or `Pair.of` implementation. In particular `List` must not
silently alias an Array, `Vector` must retain its declared shape,
`Set` requires a reviewed key identity/uniqueness policy,
and `Entry`/`Pair` must retain their independently agreed nominal/positional
identity. The separate Java Entry implementation is PR #1668; its code
must be reconciled with this branch instead of copied as a parallel runtime.

## Compatibility

This branch adds `~array(...)` as a strictly additive synonym for `Array.of(...)` and `[...]` array construction. It does not modify any existing `[...]` array, `(...)`
tuple, `tuple {...}`, `~tuple(...)`, `~record(...)`, `~map(...)`,
`Array[T]`, `Vector[T]`, `Tuple[T1,T2]`, fixed or repeating collection
shape, named generic parameter, or type-alias syntax.

## Exact-head gates

1. Source SHA read-back, no unexpected changes, and dependency-head review.
2. `mvn -B -ntp -P!native-thread-unix -Dtest=TildeRecordLiteralGuestTest,TypedEmptyMapGuestTest,NonemptyTildeMapGuestTest,CollectionOfFactoryGuestTest test` with Java 21 and genuine Truffle guest execution. Verify nonzero workflow steps, test counts, and exact source SHA independently.
3. Compiler type/ownership negatives, malformed key domain, duplicate-key transactional construction and actor transport regression suites.
4. JDK25/GraalVM Native Image and independent LLVM, Wasm, Forge, .NET guest conformance. All are outstanding.

**Symbol-resolution merge blocker:** the parser-level factory rewrite is not yet proven to discriminate reserved built-in factory owners from user-declared/imported or shadowed `Tuple`, `Array`, `Map`, and `Record` namespaces. Before merge, retain nominal user-defined `Tuple.of` semantics, add lexical/import shadowing tests, and lower only after proof of compiler-owned identity. This is additional to execution gates.

No certification, full-AOT guarantee, or five-backend equivalence follows
from this source-only branch.

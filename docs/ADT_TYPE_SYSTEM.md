# Algebraic data types: soundness and conformance gates

Status: **partial implementation**, not a claim of Java/LLVM source parity.
This document pins the required language invariants and the executable
Java coverage currently added by the ADT exhaustiveness hardening work.

## Algebraic model

Oreslang must preserve the distinction between:

- **Product** (AND): a tuple or fixed record has all of its fields.
  For finite domains, `|(A, B)| = |A| * |B|`.
- **Sum** (OR): exactly one variant is active, including its typed
  payload. `|A + B| = |A| + |B|`. Variant identity is nominal for
  user-defined enums and does not depend on payload shape.
- **Zero/bottom**: the uninhabited type, if introduced, has no values.
- **One/unit**: a zero-field record/nullary case can have one value;
  `void` is **not** automatically a first-class unit value.
- **Functions**: `|B^A|` counts mathematical total functions over finite
  domains; observable effects, divergence and runtime function identities
  cannot be ignored when making a practical equivalence claim.

A type is not a sound closed sum merely because two fields are nullable
or a type is annotated with a union-shaped comment. Its domain must be
represented in the checker, runtime and wire schema.

## Current Java source capabilities

- Generic `Option<T>` with `Some(T)` / `None`, and `Result<T,E>`
  with `Ok(T)` / `Err(E)`, plus affine ownership checks for payloads.
- Tuples, explicit/inferred record/struct shapes and finite type unions.
- Ordered `match x over ... on P -> { ... } end`, with exhaustiveness
  admission and conservative rejection when coverage cannot be proved.
- `x matches P`, nominal `is type` refinements, and `switch` for
  constant equality dispatch (not payload destructuring).
- Runtime sum extraction and match tests in Java.

**Not yet guaranteed on Java `main`:** arbitrary source-defined tagged
enums with payloads, source-declared bottom/unit algebra, recursive named
ADT layout/drop correctness, value-producing typed `match` parity, or
portable serialization for arbitrary user ADTs. Active PRs/design
branches must not be mistaken for merged production behavior.

**Not yet guaranteed on LLVM `main`:** source-level parity for all of
the above; codec/wire tests alone do not prove compiler lowering.

## Coverage proof (mandatory safety invariant)

For `Option<T>`, `Some(p)` covers the `Some` branch **only if** the
payload patterns collectively cover every value of `T`. The same is
true for `Ok(p)` over `T` and `Err(p)` over `E`.

`Some(0), None` is **not** exhaustive for `Option<int>`. Neither are
`Ok(0), Err(_)` for `Result<int, E>` or
`Some(Ok(0)), Some(Err(_)), None` for
`Option<Result<int, E>>`.

The first conservative proof supports an irrefutable payload binding
or wildcard, nested Option/Result constructors, and the complete finite
Boolean partition `true | false`. It is sound to reject a complex
pattern partition until a stronger proof implementation exists; it is
**never** sound to accept incomplete coverage. Guards do not establish
unconditional coverage. Coverage of an unconstrained generic or
`Unknown` cannot be inferred from assignment permissiveness.

Nonexhaustive matches must be compile-time errors, not silent runtime
fallthrough; missing guest variants during schema decoding must fail
closed. Match reachability and exhaustiveness are separate proofs.
**Cumulative reachability matters**: `true`, `false`, then `_` has
a dead third arm even though neither earlier arm alone subsumes it.

A runtime nominal/type-pattern coverage check **must not** rely solely on
assignment compatibility. In particular `int` can widen to `float` in an
assignment, but an `int` runtime value does not satisfy `is type float`.
For a union, every alternative must be independently covered by a
runtime-compatible pattern. Otherwise `on float x` could falsely prove
exhaustive matching of `int | float`.


## Required future general ADT conformance

1. **Declarations:** introduce dedicated, closed, nominal tagged sum
   metadata (type identity, case identities, payload product shapes,
   generic arities and visibility); keep transparent type unions
   distinct from constructible enum cases.
2. **Construction:** reject unknown/duplicate cases, wrong arity,
   incompatible payload type, illegal borrow capture, invalid
   recursive-by-value shapes and type-param escape.
3. **Matching:** prove coverage of *nested payload constraints*; reject
   nonexhaustive and dead arms; preserve binding scopes, immutability,
   moves/borrows, and exact guard semantics.
4. **Genericity:** test `Option<Result<T,E>>`, product-of-sums,
   sum-of-products, nested types, and positive/negative recursive
   definitions. Define explicit variance and reification semantics.
5. **Lifetime:** destructors run exactly once on the active variant.
   Inactive variant storage cannot be read or dropped; no moved
   payload may be reused, and GC/actor heaps must trace the active
   branch only.
6. **Runtime ABI:** choose alignment, tag width, niche optimizations
   and cross-FFI ownership rules independently of wire representation.
   Unknown tags and invalid padding must never lead to type confusion.
7. **Wire/actors:** schema identity and explicit stable case tags
   precede message admission; recursively derive codecs for each
   payload only when `Serializable` is proven. Reject capabilities,
   stale actor generations and oversized/untrusted frames.
8. **Backends:** run shared positive/negative source tests on both
   Java/GraalVM JVM/AOT/hybrid and LLVM JIT/AOT; assert equal output,
   diagnostics class and ownership behavior, not merely parse success.

### Minimal property / fuzz corpus

- Boolean product: `bool * bool = 4` states.
- Boolean sum: `bool + bool = 4` states **with distinct tags**.
- `Option<bool> = 3` states, exhaustive `Some(true)`,
  `Some(false)`, `None`.
- `Result<bool,bool> = 4` states.
- `Option<Result<bool,bool>> = 5` states.
- `Option<int>`: `Some(0), None` must reject; `Some(_), None`
  must admit.
- Every guard-only variant cover must reject.
- A wildcard default must admit; any later arm it subsumes is dead.
- A nested constructor may never hide a refutable child pattern.
- A move-only payload may be consumed only once across every arm.
- A generic payload must not be treated as `Unknown` == `int`.
- Host-reflection or C++ RTTI cannot create Oreslang nominal type proof.
- Encoding and decoding preserve nominal tags even for equal payloads.

See `docs/PATTERN_MATCHING.md`,
`src/test/java/dev/oreslang/PatternMatchingHardeningTest.java` and
`src/test/java/dev/oreslang/OptionResultSemanticsTest.java`.

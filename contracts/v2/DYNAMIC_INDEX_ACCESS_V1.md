# Dynamic bracket access — checked field-view contract (Java/Graal v1)

Status: draft implementation on Java/Graal only. The compiler and runtime have **not** passed exact-head Maven/JUnit/GraalVM/native-image verification. LLVM/Wasm/formatter and other frontends are not certified. This document does not enable bare `record {...}`, `Pair<K,V>`, or `Entry<K,V>` in the parser.

## Read contract

- A statically resolved closed structural record or struct accepts a string key. Literal keys select one exact declared field type. Runtime-selected string keys produce the union of all fields visible through the receiver's **static** type.
- A nominal non-actor class accepts a string key for visible data fields. Public fields are available outside the class, private fields only within their declaration owner. Inherited generic field types are substituted before union formation. Methods, actor state and reflective metadata are not indexable.
- Constructor indexing through `self` is rejected at type checking until a path-sensitive, field-specific definite-initialization proof exists. The guard recursively walks all current expression forms and nested statement-bearing lambda/callback bodies; because the AST is sealed, unknown future forms fail closed until explicitly handled. A constructor lambda is also rejected at type checking when it captures `self`: lambda bodies execute later, and dropping constructor context while checking them must not defer an indexed read of partially initialized `self`. Constructor-local bindings/destructures also cannot directly alias or package `self` through identity-preserving casts, conditionals/match values, borrows, traps, lists, tuples, or records; ordinary derived member reads such as an already-initialized `self.value` remain governed by the normal definite-initialization rules. This mirrors the ownership rule that ordinary closures and local values cannot capture or move the constructor's borrowed `self` until an explicit non-escaping construction-capability contract exists. Construction-scoped `self` also cannot be converted into a `rt borrow`, `rt copy`, `rt take`, or `rt share` alias, including through a self-rooted projection. The ownership checker independently rejects borrow places whose ownership root is the partially initialized constructor `self`, so a local borrow alias cannot bypass the indexed-read guard. Ordinary methods may index initialized `self` under their lexical access permissions; runtime checks remain authoritative.
- Structural method contract keys (internal `instance$…$arity…$generics…` entries) are excluded from indexed data fields. Neither static nor computed keys may turn such contracts into first-class method values.
- A tuple or fixed positional product accepts an integer index. Literal indexes must be within arity; dynamic keys return a union of element types and require runtime bounds checks.
- Arrays and lists retain their existing integer indexing semantics. No implicit conversion of strings to indexes, or indexes to strings, occurs.
- The runtime evaluates the receiver and key once in left-to-right order, then dispatches. Missing, inaccessible and uninitialized fields fail explicitly.
- For a partially initialized explicit struct, a compile-time-known literal key can read an already initialized field. An unknown key cannot be proven to avoid uninitialized fields and is rejected until the entire value is initialized; an explicitly uninitialized literal key is always rejected.

## Hidden-field security

A closed record's static type is a **view**, not a promise that the physical backing container has no extra fields. Width projection through a cast, parameter, or return must never enable access to omitted fields via a dynamic key.

Every closed-record/class IndexExpr is checked by the frontend and receives a weak AST-identity-bound immutable set of permitted field names. The evaluator requires that proof *before* performing Map/OresObject access. Same-text AST nodes never share authority; compiler rewrites explicitly propagate only existing checked key sets, never mint new authority. Class runtime access independently repeats nominal field visibility and uninitialized-field checks. No host/Java interop is permitted through this path.

**Backend obligation:** Any compiler pass cloning IndexExpr must either propagate the checked access proof or re-run full static checking on the rewritten AST. Failure to propagate must fail closed, not turn into unchecked access. For native lowering, a proof becomes a layout-specific fixed key table/bounds check, not a free reflective map lookup.

## Compile-time constant key specialization

- An immutable `const` string or integer binding initialized by a literal, or an immutable alias of another proven key, may be specialized without evaluating guest code at compile time.
- Structural/class `object[key]` is certified for precisely the selected visible field when the string key is proven. A runtime mismatch still fails the closed-view key guard; mutable/rebound keys never inherit constant-key privileges.
- Signed, immutable integer aliases are folded conservatively; known negative and out-of-range tuple slots are rejected at compile time.
- Tuple `tuple[index]` uses a checked positional slot when its integer key is proven and in range. The proof is AST-identity bound, propagated only through compiler-maintained rewrites, and **also checked against the runtime-evaluated index**. This prevents a stale rewrite from selecting a different affine slot.
- Field-key and tuple-slot proofs are incompatible at any one indexing site. A compiler optimization must preserve exact proof semantics or retypecheck the rewritten expression.
- Runtime indexing dispatch must preserve the **kind** of a certified view. A record/class-certified AST site cannot fall through to the list/array sequence path if an erased or malformed value changes receiver representation; the positional guard rejects such a mismatch. Uncertified normal array/list sites remain legal and retain integer and bounds checks. Tuple-certified sites still enforce their exact known slot. This is defensive runtime protection, not a substitute for type/ownership checking.
- A compiler-certified projection proof is bound to the **exact checked receiver and selector AST nodes**. A post-check optimizer may not replace a computed selector with a literal—even a value-equivalent in-domain literal—and inherit the old authority, because the replacement has not passed type, visibility, ownership, constructor, or borrow admission. Literal contradiction checks remain defense in depth, but any changed selector requires a fresh front-end proof.
- A rewrite from an **unchecked original AST site** must not reuse a proof already present on its destination AST object. The proof-propagation helper rejects unchecked-to-proven rewrites, leaving existing destination proofs unchanged. Unchecked-to-unchecked clones remain valid for ordinary array/list indexing.
- Compiler-certified projection proofs cannot move to a **different receiver or selector AST node**, even if the replacement is structurally equal: equal AST text is not proof of the same lexical binding, typed view, constant provenance, or ownership state. The proof registry requires exact receiver-node and selector-node identity before publishing authority. The tree shaker preserves both original checked nodes whenever ordinary constant folding would replace either one. Its constant-call inliner conservatively declines functions whose returned expression contains indexing, including indexing nested inside another expression. The substitution helper independently treats any already-certified `IndexExpr` as an opaque checked unit and does not descend into either child, so a future substitution caller cannot silently replace a parameter underneath pre-existing authority. Only a new `IndexExpr` wrapper around the identical checked receiver and selector nodes may inherit the proof; semantic rewriting of either child requires explicit re-type-checking and re-authorization in a future optimizer.
- The evaluator now caches each immutable compiler-certified field plan by `IndexExpr` object identity in its own synchronized `IdentityHashMap`. Actor carriers can access the cache concurrently. The global weak-identity proof registry is consulted only on the first access per evaluator and AST site. A missing proof is never cached; cache hits must still validate the **requested key**, not just the receiver or prior successful key.
- The cache is bounded by the static indexing sites reachable by the evaluator's resident program. This is a localized performance optimization, **not** a new permission source; a fresh evaluator obtains proof authority from the type checker and compiler only.

## Ownership and projection integrity

- After type checking, the ownership checker uses the **same checked AST-identity key set** for record/class read projections. A private field or hidden structural member must not influence the selectable type union for an outside caller.
- Indexed field reads for classes, structs, records, and immutable tuple products currently require every statically selectable field to be `Copy`-compatible. A literal index needs only its resolved field to be `Copy`-compatible. This prevents repeatable move-only extraction until place-sensitive indexed moves and exclusive projected borrows have been proven.
- A computed index must never read an affine field through a narrowed record, an inherited class shape, a protected mutex view, or an actor capability.
- Plain mutable Array/List indexed operations continue to follow the pre-existing collection rules. Affine element moves from mutable collections require a separate audit and are not claimed safe or implemented by this patch.

## Writes

Tuple indexed assignment must be statically rejected, as fixed tuple runtime values are immutable products. Arrays and lists retain mutable indexed assignment when types and owner permits. Bracket writes to nominal class fields or structural fields remain disabled in this phase; a future admission gate must prove mutability, borrow exclusivity, field-type assignment for each runtime-selectable key, and constructor definite-assignment constraints before enabling writes. No runtime fallback to an unchecked map write is allowed.

## Additional planned product types

- `Pair<A,B>` is a positional two-field product: `p[0] : A`, `p[1] : B`, and `p[i] : A | B` for a runtime integer, with checked bounds.
- `Entry<K,V>` is a named pair: `entry["key"] : K`, `entry["value"] : V`, and `entry[s] : K | V` for a runtime string. Numeric aliases `0`/`1` are a separate syntax decision.
- Neither type is declared or lowered by this PR; tuple-of-two already supports the positional subset.

## Deferred mutable indexed projection ownership

The existing `OwnershipAndClosureTest.ordinaryIndexedProjectionIsAReadNotAnImplicitPartialMove` explicitly admits a record alias obtained from `items[0]`. Tightening all `Array/List<T>` indexed reads to require `Copy<T>` would regress this currently specified behavior. Until indexed place/loan and alias-lifetime semantics are reconciled, this PR does **not** silently redefine such reads as moves. A separate ownership-model change must prove how aliases, subsequent slot updates, actor isolation and deterministic drops interact. This is **not** a claim that general mutable list extraction is fully hardened.

## Verification gates

1. Run all `CheckedDynamicIndexAccessTest` tests under JDK 25 + GraalVM with exact source SHA.
2. Execute standard Maven/JUnit, interpreter guest execution, Native Image AOT/hybrid and polyglot-isolate gates.
3. Add LLVM equivalent access plans with the same static-view enforcement and signed bounds safety.
4. Exercise inheritance, structural width-narrowing through parameters/casts/returns, non-escaping borrows, private fields, actor isolation, cancellation, and optimizer cloning.
5. Fail closed when source-only semantics and backend implementations are missing; do not merge a security change on runner-admission failures.

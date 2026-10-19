# CPU data-oriented actor compute call surface — syntax admission contract (proposal)

Status (2026-10-19): **not an executable Oreslang API**. The parser change in this
branch admits only anonymous `fnc(chunk) { ... }` as the existing `LambdaExpr`.
Inline `[...]` and `#symbol` literals already parse on Java main. Generic
type-qualified factory calls, named runtime arguments, typed `Motion.field`
descriptors, `+=`, scheduler policy and `Region<T>` compute lowering remain
independent milestones. Do not mark this end-to-end example as passing.

## Target source (future, with deliberate trailing-closure exception)

```ores
let scheduler = Scheduler.new(
    workers: #auto,
    affinity: #soft,
    topology: #numa_aware,
    work_stealing: #hierarchical,
    task_migration: #safe_points
);

let group = ActorGroup.new(
    scheduler: scheduler,
    sharding: #cpu_local,
    batch_dispatch: true
);

let motion = Region<Motion>.new(
    layouts: [#aos, #soa, #aosoa],
    authority: #soa,
    tile_size: 32,
    placement: #numa_local
);

await group.compute(
    region: motion,
    layout: #aosoa,
    reads: [Motion.vx, Motion.vy],
    writes: [Motion.x, Motion.y],
    fnc (chunk) {
        chunk.x += chunk.vx * dt;
        chunk.y += chunk.vy * dt;
    }
);
```

This exact future syntax requires all of the conditions below; accepting a
subset of tokens as ordinary expressions is **not** evidence of working code.

## Syntax/lowering layers and status

1. **Anonymous block function (this PR):** `fnc(chunk) { ... }` and
   `nlex fnc(chunk) { ... }` parse to the *same* `Ast.LambdaExpr` as
   `|chunk| -> { ... }`, using exactly the same inferred/typed parameter
   representation, capture rules, explicit-return rules and contextual typing.
   There is no new callable category, implicit return, permission to capture
   actor state, or nested declaration. The form requires a block; `fnc(x) -> x`
   is invalid. `fnc(int x) { ... }` also works. Java implementation staged
   in this branch; LLVM and formatter parity are not established.
2. **Inline lists:** `[expr, expr]` is already `Ast.ListExpr` in Java.
   The list elements are evaluated in source order. A typed consumer must
   decide whether a particular array is a borrowed slice, owned list or
   fixed-size configuration. `[#aos, #soa]` is *not* a special enum syntax.
3. **Runtime named arguments:** this stacked branch now parses `name: expression`
   into a first-class staged call-item AST and preserves lexical source order.
   Type/ownership/runtime paths deliberately reject the staged node, so named
   calls are **not yet executable**. The broader optional/default/rest binder
   work remains in Java PR #1335. AST must preserve label, source position,
   omission and spread; binding is case-sensitive, statically checked and
   lowered to formal positional ABI slots. Evaluate each actual exactly once
   in lexical source order, then permute temporary values, never expressions.
   Preserve ownership/effects/cancellation, class overrides and overload-arity
   semantics. Named type metadata `<name=value>` is separate.
4. **Symbols:** `#auto`, `#numa_aware` and `#aosoa` already lex and parse
   as bounded, NFC-normalized, immutable **process Symbol values** on Java.
   `#"compound-name"` is the quoted spelling. Symbols are *not* identifiers,
   macros, strings, or compiler-recognized scheduler policies on their own.
   Policy APIs must validate an exhaustively enumerated set of Symbol values
   with typed rejection for misspellings; `#unsafe_setting` cannot be silently
   accepted, and an untrusted caller cannot thereby acquire scheduler authority.
5. **Named + final anonymous callback:** this stacked branch recognizes one
   source-marked final anonymous `fnc`/pipe closure after named arguments, but
   keeps it fail-closed until signature binding proves the final callable formal. The target
   `compute(..., writes: [...], fnc(chunk) {...})` needs an explicit,
   narrowly-scoped **trailing-closure** rule: allow exactly one final,
   syntactically anonymous callable (`fnc(...) {...}` or `|...| -> {...}`)
   only if static overload resolution proves the single remaining final formal
   is a callback parameter. All other positional-after-named calls are errors;
   no spreads or omissions in this v1 exception. A well-defined implementation
   must introduce source-anchored `TRAILING_CLOSURE` call-item metadata
   rather than silently treating all unlabeled arguments as legal. A completely
   explicit alternative remains `body: fnc(chunk) {...}` with a declared
   `body` callback formal, which **does not** need this exception.
6. **Static generic factories:** this stacked parser branch recognizes
   `Region<Motion>.new(...)` as a `TypeExpr(TypeRef)` receiver followed by
   ordinary member access. The qualifier is not a runtime class object and all
   semantic/runtime consumers currently reject it until static type-member
   resolution is implemented. Do not confuse `Region<Motion>` with
   `Region<Motion>(...)` generic method instantiation. A safe first executable spelling is
   `new Region<Motion>(...)` *after* a concrete constructor API exists.
   Any future `.new` on `Type<T>` must resolve to a factory with checked
   generic arguments, not parse as angle-bracket comparisons.
7. **Member field selectors:** `Motion.vx` inside `reads: [...] / writes:
   [...]` must resolve to a typed, compile-time `Field<Motion, Scalar>`
   descriptor with nominal field identity, offset/layout metadata and an
   exported/visible field declaration. It must not be treated as reading a
   shared static value. Resolve it via an explicit compile-time schema
   descriptor namespace or a context-checked projection, never broad dynamic
   member lookup or forged field names.
8. **Compound writes:** this stacked parser branch now tokenizes and preserves
   `+= -= *= /= %=` as `CompoundAssignExpr` without desugaring. Type,
   ownership and runtime paths reject the node until verified compound-place
   semantics exist: evaluate the lvalue receiver/index once, acquire
   one scoped mutable loan, read the old value, evaluate RHS once, checked
   operation, then write; never synthesize `a().x = a().x + rhs`. Region
   chunk fields are lane views and may require SIMD masked operations, not a
   scalar host read-modify-write.
9. **Runtime API:** `Scheduler`, `ActorGroup.new`, `Region<T>` and
   `group.compute` in this form require actual stdlib/runtime declarations,
   capacity-limited placement/scheduling, generational lifetime guards,
   provenance-checked region loans, and Java/LLVM backend implementations.
   Parser admission alone must **not** make missing functionality silently
   fall back to unsound host operations.

## Execution and ownership contract

- ActorGroup membership grants **no** automatic heap/region mutation
  capability. The region owner issues scoped, disjoint per-chunk write permits
  for exactly `writes`, and read permits for exactly `reads`. No concurrent
  conflicting writer, structural edit, alias escape, capture of state loans or
  mutation from a separate actor domain. The host scheduler cannot forge
  capabilities by recognizing `#cpu_local`.
- A compute callback is a guest-checked, non-escaping `fnc(Chunk<Motion>)`
  with no `await`/yield while holding a mutable chunk loan in v1. Captured
  `dt` must be immutable, Send-safe, ownership-verified and preserved for the
  dispatch lifetime. Actor persistent data remains in the single `state {}`
  schema, accessed only through sanctioned critical state-loan APIs.
- Each chunk is a disjoint bounded range. `chunk.x`, `chunk.y` etc. are
  checked projections of the same range; `chunk.vx * dt` broadcasts a scalar
  only when scalar/vector types agree. Compiled loops must check indices,
  vector tail masks, overflow/alignment and cancellation at safe points.
- `#auto` respects process cpusets, container quotas and a configured
  maximum, never OS-thread count as trusted tenant authority. `#soft`,
  `#numa_aware`, `#cpu_local`, `#numa_local` and
  `#hierarchical` are performance preferences, not pinning guarantees.
  `#safe_points` migrates only fully yielded, non-critical tasks; no
  cross-thread continuation may concurrently access an actor/region loan.
- `layouts: [#aos,#soa,#aosoa]` declares permitted physical layouts; only
  `authority: #soa` is authoritative at an epoch. Others are lazily or
  explicitly materialized with version/fence checks and memory budgets.
  Conversions are observable work, not free zero-copy casts. The actor-group
  API cannot obtain a different mutable view while the old loan is live.
- Faults, cancellation and partial completion must have defined semantics:
  v1 either stages per-chunk mutations and commits an epoch after all chunks
  succeed, or explicitly documents partial-commit results with a typed failure
  and which chunks committed. No implied transactionality. Actor CONTROL
  messages and shutdown must remain serviceable under work stealing.
- Return `Future<ComputeResult>` (or a versioned typed result) that can be
  `await`ed, with clear success/failure/cancellation and epoch identity. Do
  not treat `await` as sufficient to prove physical quiescence of cancelled
  worker continuations.

## Mandatory positive tests

- Anonymous `fnc` ↔ pipe AST, contextual types, zero/one/many typed and
  inferred params, lexical capture, `nlex` capture rejection, explicit
  return, nested calls, error unwinding, tail/cancellation semantics.
- Inline list order and type of Symbol elements; `#_x`,
  `#"quoted-name"`, and wrong-policy symbols reject in API validation.
- Named calls reordered, mixed leading positional, final-closure exception,
  constructor/method/overload/visibility, no runtime map dispatch.
- Region template qualifier, verified field descriptor type/ownership,
  `+=` single lvalue evaluation, index once, SIMD tails, migration fences.
- Java Graal interpreter, Native Image AOT/hybrid, LLVM O0/O2, and formatter
  must all pass the **same** source fixtures, including the full example,
  before asserting this API works.

## Mandatory negative tests

- Duplicate/unknown named args, ordinary positional after named, two
  trailing lambdas, named+spread, omitted final callback, unsupported
  dynamic callable labels; malformed `#` literals and unknown policy values.
- Writable field missing from `writes`, read outside `reads`, duplicate
  or conflicting aliases, invalid chunk scope, stale generation, cross-actor
  stolen permit, mutable captured state, `await` while loan live.
- Illegal AoS/SoA/AoSoA conversions during outstanding writes, impossible
  NUMA placement, worker budget exhaustion, silent fallback to GPU/host
  execution, failure after some chunks and shutdown races.

### Tracking / nonclaims

- Existing named-call implementation tracker:
  https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1336
- Java named-call draft: PR #1335 (does not yet execute named guest calls).
- Java tri-layout host reference: PR #1477 (not a source-language Region).
- ActorGroup columnar host reference: PR #1478 (not a source-language compute).
- Existing `docs/NAMED_ARGUMENTS_CONTRACT.md` must be amended before enabling
  the trailing-closure exception: it currently forbids positional-after-named.

This file defines a proposed contract, **not** evidence of compiler admission,
native execution, correct NUMA scheduling, or cross-backend parity.

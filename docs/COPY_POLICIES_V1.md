# Copy policies, instance noncopyability, and singleton identity — v1 proposal

Status: **design / conformance contract only; NOT compiler support** (2026-10-30).
Target backends: Java/GraalVM, LLVM, .NET NativeAOT, Wasm, JS.
No new syntax below may be treated as implemented until an exact-head compiler conformance run passes.

## Existing behavior and compatibility

* `OwnershipChecker` already treats ordinary class values as move-only by default,
  and rejects `rt copy` of move-only values pending a real copy contract.
* `rt take`, `rt borrow`, `rt mut borrow`, and `rt share` are distinct
  operations. **Never** rewrite a forbidden copy into a share or an implicit move.
* Field annotations are represented by `Ast.FieldDecl.annotations`, but class
  annotations are currently rejected/discarded by the parser and `ClassDecl`
  has no annotations slot. `define singleton` and `NoCopy<T>` need grammar,
  AST, linker, and type-checker work, not a documentation-only enablement.
* Existing `Copy` and `Clone` terminology in OWNERSHIP_SMART_POINTERS_DOP.md
  is a separate capability contract. This proposal does **not** enable
  implicit class copying or unconstrained class cloning.
* The intended language-facing borrow spelling is `rt borrow` and
  `rt mut borrow`; no new pointer syntax or implicit conversions.

## Core semantic model

Copy policy is statically computed on **places/values**, not an object-wide
runtime boolean. The compiler represents:

1. `CopyCapability` = `ALLOWED` or `DENIED(reason, origin)`.
   A class is denied by default; only a verified, explicitly declared
   duplication contract can make an ordinary class copyable.
2. `CopyLint` = `NORMAL` or `WARN` (independent of legality).
   A lint must never turn an illegal copy into a warning-only operation.
3. `OwnershipDisposition` = `OWNED`, `BORROWED`, `SHARED`,
   `MOVED`, or `SINGLETON_ANCHOR`; plus an optional noncopyable
   **provenance seal** retained through generic and container operations.

These are compile-time facts. Oreslang must not add a per-object heap flag
or runtime branch to decide whether `rt copy` is legal.

### Language surface (proposed, not yet parsed/checked)

| Surface | Copy behavior | Other operations |
| --- | --- | --- |
| `@NoCopy` on class | Any owned duplicate forbidden | borrow/move permitted |
| `@NoCopy` on field | Cannot duplicate that field; aggregate copy denied transitively | read/borrow permitted; moving field only with existing projection rules |
| `@WarnOnCopy` on type/field | Copy allowed **only if otherwise proven copyable**; emits warning at explicit copy | move/borrow unaffected |
| `@Copyable` on class | Opt-in **request**, not proof: accepted only with verified compiler-generated or explicitly specified duplication contract | no shallow copy of owning resources |
| `NoCopy<T>` | Consumed, sealed owned instance; never `Copy` even if `T` is `Copy` | `rt take` preserves seal; `rt borrow` views preserve it |
| `define singleton S ... end` | Intrinsically no-copy; one logical identity per declared scope | not independently constructed, taken, cloned, or serialized as a second identity |

`@NoCopy` on a field restricts *producing a second independently owned
value*, not harmless temporary reads. A primitive's read-only arithmetic
use is allowed, but storing a duplicated owned value or applying
`rt copy` to that field is prohibited. The same rule applies to a field
access through nested projections.

### Copyability derivation

* Scalar and immutable structural value kinds follow their existing
  intrinsic `Copy` proofs, unless their **place** is explicitly restricted.
* Arrays, maps, owned resource handles, actors, classes, and their
  transitive aggregates are not silently copyable.
* Copyability of an owned aggregate is conjunction of copyability of its
  constituents **and** absence of a field policy forbidding duplication.
* Recursive class graphs, generic instances, unknown/unresolved imports,
  dynamic erasure, and unproved copy/clone implementations fail closed.
* `@Copyable` must be rejected for actors, singleton anchors, or types
  containing a forbidden owned field. Implementing a named `duplicate()`
  helper does not grant permission to invoke `rt copy`.
* No implicit conversion, user-defined copy operator, or operator
  overloading is introduced.

### Instance-specific no-copy sealing

Proposed constructor is `rt nocopy <owned-expression>` with static result
`NoCopy<T>`. It **consumes** the only owned source; existing live aliases
or borrows that prevent ownership transfer make the seal illegal.
A separate assignment `val a: NoCopy<T> = t` does **not** silently seal.

* There is no implicit cast `NoCopy<T> -> T` and no safe generic
  `unwrap` that yields a copyable `T`; both would bypass the guarantee.
* `rt take sealed` yields another `NoCopy<T>`, invalidates the source,
  and leaves no second owner.
* `rt borrow sealed` returns a scoped, noncopyable view. Ordinary
  borrowing APIs can access the underlying `T` for the borrow lifetime
  without exposing a copy-capable owned `T`.
* `rt share` may create aliases to the **same** object only with existing
  Share/Send/Sync, isolation-domain, lifetime, and mutability proofs.
  Sharing never duplicates the protected value. The noncopyable seal
  remains part of the shared payload's type/provenance.
* Generic substitutions, returns, closures, async captures, containers,
  dynamic traits, imported public ABI, and FFI handles preserve the seal.
  Erasure, reflection, serialization, or a cross-backend call cannot
  downgrade it to a copyable value.
* Distinguish **replicating bytes/snapshots** from sharing identity:
  serialization or snapshot APIs that would materialize a second owned
  protected object are illegal. A separately typed immutable snapshot
  is allowed only via an explicit audited projection contract that
  cannot reconstitute the protected ownership/identity.

### Copy versus move: fail-closed partial field ownership

`rt copy` asks for a new owned value; `rt take` asks to transfer the
**same** owned value and invalidate the original owner. A class annotated
`@NoCopy` remains moveable as a **whole owned value** unless another
independent move restriction applies. Do not treat `consuming=true` as
proof that an operation is copying.

For **any class field projection**, including `@NoCopy` and ordinary
mutable fields, `rt take owner.field` is allowed **only after** the
compiler can prove field-level invalidation, reinitialization, loan
exclusion, and cleanup along every control-flow path. Until that
ledger exists, emit `E_MOVE_UNPROVEN` (not `E_COPY_FORBIDDEN`).
Immutable fields remain non-moveable under their existing rules.
A member access wrapped in a cast, ternary, or value-match arm
also cannot bypass this explicit `rt take` rule. In the current
class-reference model, an ordinary `holder.child` projection without
`rt take` is a **reference aliasing read**, not an implicit partial
move or a duplicate of the heap object. Existing alias reads therefore
remain accepted; they do not invalidate the owner. A `@NoCopy` field
also prohibits an otherwise-copyable owned duplication through a
return, argument, cast, or `rt copy`. Scoped `rt borrow` remains
legal and must observe loan/exclusivity rules.
This is a deliberate fail-closed limitation, not a claim that moves
and copies are semantically equivalent.

Dynamic property selection over classes with protected fields must also
fail closed when the compiler cannot prove the selected field is safe;
casts and projection wrappers cannot erase no-copy provenance.

### Singleton definition and scope

`define singleton S ... end` declares a compiler-anchored instance,
not an instantiable class alias or merely a static method namespace.
There is one identity **per declared isolation scope** (default:
runtime/isolate; tenant-scoped in tenant runtime namespaces). It is not
implicitly process-global across tenants.

* Copy, move/take, independent `new`, clone, duplicate serialization,
  and generic `T: Copy` admission must be compile-time errors.
* Borrows are permitted; mutable access follows actor isolation and
  exclusive-loan rules. A singleton is **not** automatically Send/Sync/Share.
* `rt share` of singleton state requires explicit safe sharing-domain
  admission; it shares identity, never creates a second singleton.
* Module initialization is once per scope, with deterministic ordering
  and incarnation fencing; hot reload and tenant reincarnation cannot
  accidentally expose two live identities for the same scope.
* A singleton transferred into another scope is not a move: the caller
  must pass a scoped capability/ActorRef with the appropriate authority.
* Singleton names must have stable linker identity, including across
  packages; no duplicate public singleton definitions with same symbol.

### Diagnostics contract

| Code | Severity | Trigger |
| --- | --- | --- |
| `E_COPY_FORBIDDEN` | error | copying a `@NoCopy` class or field |
| `E_COPY_TRANSITIVE` | error | copying an aggregate containing a forbidden owned constituent |
| `E_COPY_INSTANCE` | error | copying a sealed `NoCopy<T>` value via any path |
| `E_SINGLETON_COPY` | error | copying a singleton |
| `E_SINGLETON_IDENTITY` | error | constructing, moving, cloning, or duplicating singleton identity |
| `E_COPY_UNPROVEN` | error | class duplication without a verified copy contract; unknown generic/erased copyability |
| `E_MOVE_UNPROVEN` | error | `rt take` from a protected partial field when the checker cannot prove that exact field was invalidated; take the containing owner instead |
| `E_COPY_SEAL_ESCAPE` | error | losing a no-copy seal by cast, generic, export, serialization, or alias |
| `E_COPY_ANNOTATION` | error | conflicting/duplicate annotations, illegal target, or unsupported `@Copyable` proof |
| `W_COPY_DISCOURAGED` | warning | legal explicit copy of `@WarnOnCopy` value/field |
| `W_COPY_EXPLICIT` | warning (opt-in) | any legal explicit `rt copy` |
| `W_COPY_EXPENSIVE` | warning (opt-in) | compiler-proven expensive copy |
| `W_COPY_REDUNDANT` | warning (opt-in) | compiler-proven needless copy |

Diagnostics must identify the **originating field/type/provenance** and
the copy-expression location, be deterministic across backends, and offer
`rt borrow`, `rt take`, or `rt share` fixes **only** when legal.
Default compiler behavior: required errors, `W_COPY_DISCOURAGED` enabled;
other warnings opt-in. `-Werror=copy` promotes all copy warnings to
errors. Lint suppressions cannot suppress hard errors. Warnings are emitted
for source `rt copy` even when optimization could erase the operation.

### Security and realtime rules

* Treat forbidden copies through reflection, dynamic dispatch, FFI,
  generated serializers, actor mailboxes, task captures and hot reload
  exactly like direct copies; no lowering-time escape hatch.
* Copy legality is not a guarantee of bounded work. Hard RT rejects
  unbounded deep-copy/allocation even for legal copyable types.
* Copy is not sharing, and neither implies permission to access
  another actor's mutable memory. Existing actor isolation remains in force.

## Implementation sequence and exit criteria

1. **Java compiler metadata**: add class annotation retention (compatible
   AST constructor migration); validate annotation targets and conflicts;
   preserve immutable class/field policy in linked public ABI.
2. **Canonical ownership analysis**: derive copy policy by logical place,
   field, generic substitution and transitive class graph. Fail closed
   for unsupported runtime-copy contracts. No new runtime flags.
3. **Instance seal**: parse/type/lower `NoCopy<T>` and `rt nocopy`,
   prove consuming construction, enforce seal retention and prevent
   escape through borrow/return/capture/erasure.
4. **Singleton grammar/ABI**: add dedicated declaration kind and
   scope-aware linker identity, initialization, move/copy rejection,
   tenancy/isolate fencing and access proof.
5. **Compiler diagnostics**: wire warning collection through existing
   parse/type/ownership CLI/LSP paths with stable IDs and `-Werror=copy`.
6. **Cross-backend parity**: run the cases in
   `contracts/v1/copy-policy/cases.json` against exact compiler heads;
   require positive and negative tests, diagnostic code/severity,
   no runtime-copy fallback and scoped singleton isolation.
7. **Merge gate**: executed nonzero-step CI, exact head SHA, compiler
   tests plus backend conformance. No private-source cross-org CI
   mirroring without independent transfer authorization.

## Explicit non-goals

* Implicit cloning, transparent COW, operator overloading, runtime
  per-instance no-copy booleans, automatic `Future`/`Option` flattening.
* Using `@NoCopy` as an encryption/secrecy mechanism or claiming that
  reading a scalar value is impossible.
* Treating this spec-only change as evidence that any backend compiles
  the proposed syntax today.

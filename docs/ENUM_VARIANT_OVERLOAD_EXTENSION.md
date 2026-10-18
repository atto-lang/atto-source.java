# Enum type + variant overloads: sound static specialization (proposal, 2026-10-17)

**Status:** normative *target* amendment to `NOMINAL_ENUM_OVERLOAD_CONTRACT.md`,
not executable source-language support. The Java `EnumVariantOverloadPolicy`
is a compiler-only proof oracle, not TypeChecker admission, AST binding, a
runtime vtable, native codegen or conformance evidence. Current merged Java
runtime overloads remain keyed by name and caller-visible arity. The older
2026-10-13 contract excluded `Foo.A` as an overload type; this proposal
supersedes that exclusion **only when the whole vertical slice is enabled**.
Until then, retain fail-closed source diagnostics for every same-arity pair.

## Scope and type meaning

For an eligible, closed, nominal enum `Foo` with cases `A`, `B` and `C`:

- `Foo` in a parameter type denotes **all** constructed `Foo` values.
- `Foo.A` in **type position** denotes the refinement containing precisely
  values constructed with variant A, including its entire payload if any.
- `Foo.A` in **expression position**, when A is nullary, is an enum **value**.
  For payload cases, `Foo.A(payload)` constructs a value of variant type
  `Foo.A`. Merely using an enum case name must never silently manufacture a
  distinct class, constructor overload, or runtime singleton for payload cases.
- `Foo.A <: Foo`, but `Foo.A` and `Foo.B` are disjoint. A case is identified
  by `(fully resolved enum declaration symbol ID, declared case ID)`, not by
  the case short name, wire number, payload value or structural shape.
- Type aliases and renamed imports preserve identity; generics, unions,
  borrowed enum types, imported-but-unresolved symbols, `Any` and `Unknown`
  do not establish a variant discriminator in this initial stage.
- For the first implementation, an enum eligible for overload-discrimination
  retains the 2026-10-13 primitive/nullary/fixed-primitive-tuple *whole
  declaration* eligibility restriction. Ordinary ADTs may still contain
  records, generics, nested enums, borrowable/actor capabilities, etc.; they
  simply cannot differentiate overloads yet. This restriction may be
  relaxed later with separately proved safety, never by structural lookup.

## Admission: closed and deterministic, before calls can execute

1. Partition declarations by **resolved owner/namespace**, MODULE or
   INSTANCE or STATIC domain, canonical callable name, and **caller-visible
   expanded argument arity**. `fnc`/`routine` share the MODULE domain;
   implicit constructor/method `self` never counts as an argument. Constructor
   overloads remain **arity-only** in this proposal.
2. Different arities coexist independently. Within one arity, ordinary
   non-enum parameter positions must have **exactly the same, verified,
   canonical type identity** for every pair. Different primitives, generic
   instantiations, record shapes, and return/effect annotations do not add
   slots. Initial host policy proof admits explicit scalar non-discriminators
   only; compiler integration must independently prove any wider exact type.
3. At each enum position, compare the *sets of enum cases* admitted by the
   parameter type. Different nominal enums are disjoint, distinct cases of
   one enum are disjoint, and `Foo.A` is a strict subset of `Foo`.
4. For **every same-arity declaration pair**, either their cartesian product
   parameter domains must be **disjoint**, or one entire parameter domain
   must be a **strict subset** of the other. Equal signatures and pairs with
   overlapping but incomparable domains are rejected at declaration/link
   time. Example: `f(Foo.A, Foo)` and `f(Foo, Foo.B)` overlap at `(A,B)`
   but neither contains the other: reject, do not choose by position order.
5. At least one enum position must differ in each pair. Plain `f(int)` vs
   `f(string)` remains invalid, as does `f(Foo.A,int)` vs `f(Foo.B,bool)`:
   arbitrary non-enum parameter differences are not discriminators.
6. Same-signature inheritance is a single **override slot**, subject to
   ordinary override compatibility; a child's distinct enum-case parameter
   introduces a different overload, not an override. Diamond collisions,
   visibility, static/instance separation, actor framework private entrypoints,
   effects and ownership remain separately checked.
7. Maximum 64 explicit parameters and 128 declarations per same-arity family
   in this phase; fail closed before expensive candidate comparisons.

## Calls: static specialization, never runtime multimethod selection

A call is bound only using its **statically proven** argument types, after
named-argument mapping / fixed spread expansion, before effects, ownership,
ordinary assignability and lowering. Among applicable declarations choose the
**unique smallest set** under parameter-domain inclusion. If none/ambiguous,
report a compile-time diagnostic with suggested explicit `match`/narrowing.
Never inspect runtime enum discriminants, payload values, structural members,
heap class, enum tag bytes, source declaration order or return context to
choose a declaration.

```ores
// Target syntax: not currently enabled for same-arity variant overloads.
define enum Foo as A, B, C end

pub fnc handle(Foo value, int n): void { /* general */ }
pub fnc handle(Foo.A value, int n): void { /* A specialization */ }
pub fnc handle(Foo.B value, int n): void { /* B specialization */ }

handle(Foo.A, 1);  // A specialization, statically typed as Foo.A
val Foo whole = Foo.A;
handle(whole, 1);  // general Foo, even if runtime value is A
```

A call through a variable statically typed `Foo` **cannot** automatically
select `Foo.A`/`Foo.B` specializations. If only case overloads are present,
a call with static type `Foo` **fails**, even if those cases happen to cover
all current variants. An explicit exhaustive `match` may refine the type
within arms and select individual case overloads.

Refinements can be obtained through already-approved match/type-flow proofs;
no new unchecked type downcast is authorized. After static selection, verify
the *whole* selected signature, including other params, ownership, effects,
visibility and generic substitution. Return type, expected contextual type
and conversions cannot change the selected overload.

## Staged compiler binding implementation (2026-10-17 audit follow-through)

Java draft #1242 now has a **host-only immutable prepared family** that proves
same-arity relationships at declaration time and can bind repeated static calls
without re-running quadratic candidate admission at every call site.
`EnumVariantOverloadPolicy.prepare(...).bind(staticArgumentTypes)` produces a
`BoundTarget` containing the chosen declaration signature and deterministic
slot symbol. A bound target is tied by **object identity** to its prepared
compilation-image family; `requireLinkedTo(otherFamily)` rejects stale/hot-
reloaded plan substitution even if structural signatures appear equal.

The family also exposes a stable SHA-256 **schema fingerprint** built from
sorted canonical slot tokens and every used enum's sorted full case/payload
descriptor. This is strictly an **invalidation hint**: the digest is never an
overload selector or proof of owner/case identity by itself. Any eventual
serialization/linker must compare authoritative declaration metadata rather
than trusting the hash alone. The compiler currently does **not** attach the
binding to an AST call node or consume it in the evaluator/ownership checker.

Complex ADTs remain legal elsewhere. Only enums used as overload
discriminators must have eligible payloads. Java/LLVM host policy work is
bounded at 64 visible arguments, 128 candidates per arity, 1024 enum cases
and 8320 candidates across independent arities. The Java smoke suite
includes an independently defined finite cartesian-product pair oracle,
stale-image/schema regression checks, and an exact ABI slot-token golden
shared with native LLVM draft #577.

The LLVM companion implements the same static subset relation and
symbol token for an **ASCII-only ID subset**; full canonical UTF-8 module,
enum and case identity parity remains an explicit future gate. The LLVM
native policy currently rechecks admission on each selection and must not
be used for an unbounded runtime lookup. Both policy implementations are
compiler-reference components, not enabled source-language dispatch.

A source-owned, exact-revision Java host workflow is installed in draft
#1242. A check counts as qualified only if a runner actually executes the
steps and the checked-out commit/tree corresponds to the reviewed PR head.

## ABI / dynamic dispatch / safety gates

- Store immutable target declaration/slot identity on every statically
  checked direct call and method call. Identity is a resolved declaration,
  **not** a method name, enum value, AST structural equality or hash alone.
- JIT/AOT and ownership passes must consume the *same* selected target;
  tree-shaking/cloning may only proceed if the binding is preserved or
  re-derived/verified. No fallback to first declaration/name+arity.
- Virtual instance dispatch may vary only by the concrete receiver's
  override of that preselected slot; child-only case overloads cannot change
  a call compiled against a parent receiver type.
- Linker symbols encode the resolved owner/domain/name/arity and every
  discriminator identity with injective, length-delimited bytes. Enum
  **declaration ID** and **case ID** are ABI inputs; alias spellings and
  numeric/wire case tags are not. Linker and incremental caches must
  invalidate on changed enum case availability/eligibility and vtable slots.
- Import visibility and export conflicts must be checked **before** binding.
  Do not pick the only public overload from an otherwise inaccessible mixed
  private/public family at runtime.
- Runtime/FFI/actor decoding may validate the type of a preselected call,
  but cannot scan case overloads by incoming wire tags or host reflection.
- The Java host proof's `EnumInfo` input must come from a **trusted resolved
  declaration registry**, not untrusted guest input; the standalone oracle
  is not proof that all frontend/linker paths satisfy this requirement.
- Java/Graal JDK25/JIT/Native Image AOT/hybrid, LLVM native guest execution,
  imported methods, static methods, inheritance, direct and indirect calls,
  actor-safe boundaries, and release-build conformance must all pass before
  enabling the syntax by default. Until then preserve existing rejection.

## Conformance matrix

| Same-arity signatures | Admission | Reason |
| --- | --- | --- |
| `f(Foo)` / `f(Bar)` | Accept (eligible distinct enums) | Disjoint nominal domains |
| `f(Foo.A)` / `f(Foo.B)` | Accept | Disjoint case domains |
| `f(Foo)` / `f(Foo.A)` | Accept | Strict subset specialization |
| `f(Foo.A,Foo)` / `f(Foo,Foo.B)` | Reject | Incomparable overlap on A,B |
| `f(Foo.A,int)` / `f(Foo.B,bool)` | Reject | Non-enum parameter differs |
| `f(Foo)` / `f(Foo)` | Reject | Duplicate signature |
| `f(int)` / `f(string)` | Reject | Primitive type is not an overload key |
| `f(Foo)` / `f(any)` | Reject | Opaque top type is not a static discriminator |
| `f(Foo.A)` / `f(Foo.A)` with different return types | Reject | Return types not discriminators |
| `f(Foo.A)` / `f(Foo.A,int)` | Accept | Different caller-visible arities |

Tests must cover imported/aliased homonyms, stale case symbol IDs, payload
eligibility, literal-versus-widened static type, pattern-flow narrowing,
constructor value/type parsing, exhaustive non-dispatch, crossed overlaps,
method override/diamond stability, order invariance, ownership effects and
AOT symbol collisions. No method/constructor/actor backend is declared ready
merely because the host proof oracle passes.
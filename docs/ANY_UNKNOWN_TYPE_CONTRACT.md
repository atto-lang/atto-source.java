# Any, Unknown, and incomplete type inference — Oreslang type-safety contract

Status: **stage 1, compile-time admission hardening** (not a claim of runtime generic reification or LLVM parity).

## Three different concepts (do not conflate)

1. **`Any`** is the source-language supertype for an owned, verified Oreslang
   value whose concrete type is intentionally hidden. It is *not* TypeScript's
   unsound `any`. It grants no field, indexing, calling, arithmetic, mutation,
   or implicit downcast permission. A caller must narrow with an actual runtime
   check or a compiler-proven pattern first.
2. **`Unknown`** is the source-language opaque/unverified value type. A valid
   concrete value (or `Any`) can be widened to it, but it cannot silently be
   assigned to `Any` or a more precise type. Validation must establish a
   concrete contract first. `Unknown` is not an inferred error type.
3. **`Types.Unknown.INSTANCE`** is a historical *compiler-internal inference
   hole*. It is unrelated to the source spelling `Unknown`; code MUST NOT
   reinterpret the inference hole as a checked runtime value or use it as a
   language-level escape hatch. Its legacy permissive uses still need auditing
   and replacement with an explicit typed inference-variable/unresolved/error
   representation.

The source-level hierarchy is conceptually `T <: Any <: Unknown` for concrete,
owned, validated Oreslang value types. Borrowed references, callables, mutable
capabilities and unresolved generics are *not* allowed to be concealed in the
top types until the runtime retains sufficient ownership/effect witnesses.
Stage 1 explicitly rejects type-erasing known capability families (actor
references, channels, mutexes, futures, iterators and native I/O handles),
including inside records, arrays and constructed generic arguments. A future
fully reified trusted wrapper may support these without losing authority.
`void` and raw `null` are not value instances. Oreslang's existing
`Option<T>` and `Result<T,E>` conventions remain explicit.

## Assignment and narrowing

```ores
fnc box(int value): Any { return value; }
fnc obscure(Any value): Unknown { return value; }

// Rejected: cannot assume a hidden type is int.
fnc bad(Any value): int { return value; }

// A concrete test may be used to narrow, subject to the runtime
// reifiability rules, and with exhaustive match requirements.
fnc checked(Any value): bool { return value is int; }
```

`Unknown -> Any` requires *validation*, not an unchecked cast. The compiler
must reject attempts to call a value, invoke members, or use it as an array
without first establishing a suitable checked type. A nominal `Box<int>`
must never silently become `Box<Any>` when the container is mutable; generic
parameters remain invariant by default.

## Runtime types, erasure, and GraalVM

Java's generic erasure does not require Oreslang to erase its own types. The
future common descriptor format must include stable nominal identity,
constructed generic arguments, nullability/ownership, ADT tags, readonly
views and schema/code-generation identities as applicable. Both Java/Truffle
and LLVM must observe the same type-checking results, including for empty and
nested collections. The JIT may specialize implementation layouts; that does
not change language semantics.

**Current guard:** parameterized `is` / `as` is rejected until those checks
use authentic reified guest runtime type arguments. Do not replace the guard
with Java `instanceof List` or content-sampling. Neither can establish
`List<T>` on empty or heterogeneously corrupted collections. **The checker must
inspect fully resolved alias targets**; `type Numbers = Array<int>` must not
make `value is Numbers` executable before reification. Runtime casts
for currently reifiable scalar/nominal types must be tested under execution,
not just type checking.

Source unions with a safe top normalize predictably: `Any | int` becomes
`Any`; `Unknown | Any` becomes `Unknown`. Non-boxable borrows/callables
cannot be erased by this simplification. Type checks also reject unknown
nominal names without guest runtime recognition, rather than silently testing
a Java host class or always returning false.

## Indexed-access variance and union safety

Indexing a heterogeneous tuple or record with a nonconstant key yields the
**union** of all possible field/element types, not the legacy compiler-internal
inference sentinel. That union may be inspected or narrowed, but cannot pass as
an arbitrary concrete type. Indexed writes to tuples with dynamic indices
are accepted only when every slot has an equivalent writable type and readonly
capability; otherwise they fail at compile time. A constant tuple index checks
bounds and uses the exact slot type. Dynamic indexing into an empty closed
tuple/record is rejected.

## Identifiable inference holes — first producer migrated

The empty array literal now has a unique `InferenceHole(id, EMPTY_ARRAY)`
instead of using the global compiler `Unknown.INSTANCE` wildcard. It is not
assignable to ordinary types, other inference identities or `Any`.
Only an expected `Array<T>` with a known element contract may resolve an
empty array literal, since no existing element can contradict the type.
An empty array alone cannot infer an unconstrained `T` in a generic call;
the other call arguments must supply a binding first.

This is the first migration step, not a complete inference solver:
`None`, `Ok`, `Err`, imported ABI gaps and several builtins still carry
the older sentinel; all must be migrated before removing the final wildcard.

## Conditional joins and nested structural record aliases

A conditional with distinct known branch types now joins to their explicit
sum/union type. It no longer collapses to the internal inference wildcard.
If a branch still contains an unresolved inference hole, the conditional
requires explicit resolution rather than manufacturing a concrete result.
Nested record fields read through a readonly structural view are themselves
readonly, including when assigned to a local variable; extracting
`val inner = p.inner` cannot restore a writable `inner.foo`.

This is an incremental capability fix, not a general transitive borrow system:
mutable collections and nominal reference values still require first-class
view/borrow provenance before they can safely be exposed through a readonly
structural contract.

## Source-level aggregate inference hygiene

A nonempty array, tuple or inferred record cannot embed a bare compiler
`Unknown.INSTANCE` value as a field/element. Previously, an opaque foreign or
unresolved member could become `Array<UnknownHole>` or
`Record{foo:UnknownHole}` and then exploit nested legacy assignability to
impersonate a concrete type. The new check rejects the raw unknown element
at literal construction. Legitimate *contextual* holes for an **empty**
array or an `Option`/`Result` constructor remain supported and must be
replaced with explicit inference constraints in #847.

## Readonly-origin provenance and qualified capabilities

Writes through nested member or index chains rooted in a readonly structural
parameter are rejected even when intermediate field types are mutable.
Qualified nominal capability spellings (e.g. `std.Channel`) are matched by
their final name segment for conservative `Any`/source `Unknown` boxing
rejection. Alias extraction and nested reference capability provenance still
need a complete borrow-aware type representation; do not claim deep transitive
immutability or actor-sendable boxing from these checks alone.

## Writable structural fields and inference boundaries

A record whose members are writable may not widen member types covariantly.
For example, mutable `{foo: int}` must never become mutable `{foo: Any}`,
since assigning a string through the second view would invalidate the first.
Readonly views preserve structural width and safe value covariance; compiler
generated method signatures continue to use function parameter contravariance
and result covariance **only in read-only views**. There is no string-pattern
exception for writable fields named like `instance$run$arity1$generics0`;
untrusted/user-authored field keys must never impersonate method metadata. Structural parameters are now explicitly typed as readonly
record views in the callee, so `p.foo = value` cannot silently mutate an
underlying inferred or nominal object through the read-only parameter. Future richer per-field `pub val`/writable capability
metadata should replace the coarse record-level conservative rule.

The direct unresolved compiler inference sentinel is rejected when it is the
source of a value assignment to a known type. Nested/expected-directed holes
used by `None`, `Ok`, `Err` and empty collections remain a tracked migration
under issue #847; they are **not yet** an independently proven constraint
solver. The source names `Any` and `Unknown` are reserved in generic
parameter positions and resolve ahead of inferred aliases.

## Actor, async, and channel boundaries

Opaque top types conceal callable/borrow/ownership provenance. Until a runtime
validation mechanism can prove sendability, they cannot cross actor/async
transport as uninspected values. This includes nesting inside lists, tuples,
records, and nominal generic arguments. Local channels must also refuse
opaque top payload types because they could hide borrowed or callable values.
Do not weaken the existing hardening for actor isolation or `SharedMutex`.

## Implementation plan / release gates

- Compiler: classify `Any` and `Unknown` explicitly; reject unchecked
  narrowing, property access, calls, generics covariance, transport and
  impossible/unsafe runtime type tests. Preserve diagnostics and previous
  known-type behavior.
- Eliminate the internal `Unknown` inference wildcard from all admission
  paths: imported ABI fallback, empty literals, `None`, `Result`, callable
  fallback and unresolved members. Replace it with distinct inference holes,
  compile-time errors and obligations; never silently treat it as a top type.
- Runtime: reject `is T`, `as T` and typed `on T` when `T` is a
  generic parameter without a runtime witness. Do not let Java erasure cause
  a silently false pattern or an unchecked cast.
- Runtime: intern guest `TypeDescriptor`s, reify generic arguments on
  instances and function calls as necessary, prove immutable/owned/actor-safe
  transitions and reject forged/foreign descriptors.
- Expose a checked validation/narrowing primitive for `Unknown` only after
  runtime enforcement exists. No unchecked `Unknown as Any` escape.
- LLVM: implement the equivalent descriptor ABI and serialization/FFI checks;
  do not claim parity based only on a static checker.
- Test: positive and negative compile fixtures; executed scalar casts and
  malformed boxed values; empty/nested generic checks, arrays/maps, ADTs,
  structural views, FFI, actors, hot reload, native image, ASan/UBSan and
  cross-backend parity, including performance and memory tests.

Stage 1 is intentionally conservative: generic reification, validated foreign
values, ownership-safe universal boxing, and LLVM support are **not** declared
complete just because source-level `Any` and `Unknown` now parse.

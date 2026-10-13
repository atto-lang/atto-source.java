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

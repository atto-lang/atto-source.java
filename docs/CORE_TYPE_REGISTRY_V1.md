# Java Core Type Registry v1

**Status: implementation candidate; exact-head Maven, guest and Native Image evidence still required.**

## Purpose

`CoreTypeRegistry` is the Java compiler's immutable source of truth for core and alloc type identity and safety metadata. `AutomaticPrelude` is a filtered lexical-visibility view over that registry. It is not a runtime global object, a dynamic type table or an implicit allocator grant.

The registry deliberately separates three questions:

1. **Identity:** what canonical source-level type/variant/trait does this spelling denote?
2. **Availability:** is the name visible in the selected `core`, `alloc` or explicit-only scope?
3. **Lowering:** does this backend actually implement the type and its operations?

A registry hit proves only the first. It does not make an unimplemented type executable.

## Metadata per entry

Each immutable `TypeInfo` contains:
- source spelling and canonical identity;
- source scope and declaration kind;
- minimum/maximum generic arity;
- ownership category: `COPY`, `PAYLOAD_DEPENDENT`, `BORROWED_VIEW`, `OWNED`, or `TRAIT`;
- allocation requirement;
- drop policy;
- actor-transfer/Send policy;
- Java lowering status.

Primitive spelling aliases such as `int` and `Int` (where the latter is a future contract spelling), and `string` and `String`, refer to a shared canonical identity. Aliases do not create a new ownership or Send proof.

## Important safety distinctions

### Option and Result

`Option<T>` and `Result<T,E>` are payload-dependent. The carrier is Copy only if all payload positions satisfy the compiler's Copy rule. It is Send/Sync/Share only when the contained payloads prove the corresponding auto-traits.

`Some`, `Ok` and `Err` transfer their live payload. `None` has no payload. Sum destruction visits only the active variant and disposes it exactly once. This metadata is consumed by the production `OwnershipChecker`; it is not merely documentation.

### Aggregates and containers

The drop policy distinguishes:
- `ACTIVE_PAYLOAD_ONCE` for tagged sums;
- `ALL_INITIALIZED_MEMBERS` for arrays, tuples, pairs and entries;
- `OWNED_STORAGE` for owned storage such as heap containers.

An array/tuple must dispose every initialized member exactly once. It must not be treated as if it had one active variant.

### Borrowed views

`Slice<T>` is a borrowed view. Its name cannot make it owned, Copy, or transferable. Source lifetime, reallocation, mutation and actor boundary checks remain the responsibility of the compiler's checked owner/loan facts.

### Allocator profiles

The public compiler entrypoint now has an explicit profile overload:

```java
OresCompiler.parseAndTypeCheck(source, AutomaticPrelude.Profile.CORE);
OresCompiler.parseAndTypeCheck(source, AutomaticPrelude.Profile.HOSTED);
```

The compatibility default remains `HOSTED`. The `CORE` profile rejects registry types marked `ALLOC` before lowering. This is a compiler admission check, not proof of allocator implementation.

**Java String compatibility exception:** Java already treats `string`/`String` as an immutable scalar. This registry records that under `core/primitive/string-view`, with `COPY` ownership and `JAVA_LEGACY_SCALAR` lowering. It does **not** claim to implement the owned, allocator-backed `alloc/string/String` type. A future owned Atto String requires a deliberate migration or a distinct qualified identity; it must not silently inherit the legacy scalar's Copy behavior.

## Checkers and anti-spoofing

Both `TypeChecker` and `OwnershipChecker` consult `CoreTypeRegistry` for protected identities. They reject forged core names in local declarations, imports and linked-class metadata. The type checker also validates registry generic arity and allocator-profile admission. The ownership checker derives Option/Result Copy and Task Send propagation from the registry's payload-dependent carrier identity.

The registry does not replace ordinary borrow analysis: moves, overlapping loans, use-after-take, reference escape, suspension fencing, active-variant disposal, callback effects and actor Send proofs must still be checked at source call sites.

## Tests

`AutomaticPreludeTest` covers:
- immutable and unique registry entries and canonical spelling aliases;
- Option/Result exact arity and variant drop policy;
- aggregate member destruction versus active sum destruction;
- Copy, borrowed-view, owned, allocation and Send categories;
- production TypeChecker arity rejection;
- core-only profile rejection through `OresCompiler`;
- legacy String compatibility;
- class, function, import, and linked-class spoofing in both checkers.

These tests are gates, not evidence of a passing Maven build. Do not mark them verified until the exact PR head has actual executed Maven/JUnit and linked guest steps. Native Image AOT, other backend parity and affine negative guest execution remain separate requirements.

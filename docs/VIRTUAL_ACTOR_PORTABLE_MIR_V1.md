# Virtual actor portable protected-state MIR v1

**Status:** Draft / source-authored / **NOT EXECUTED**. This changes compile-time
lowering and static call resolution, not production virtual-actor execution.

## New compiler boundary

The compiler first applies the v1 full-token/provenance contract
(`VirtualActorProtectedMirV1`), then lowers its checked Cart-like methods
through `VirtualActorPortableMirV1` into a fixed seven-op, typed, immutable
instruction stream:

1. BEGIN_EXCLUSIVE_WRITE field (turn-scoped loan)
2. LOAD_STATE_I64 field
3. LOAD_ARGUMENT_I64 parameter
4. ADD_I64_CHECKED
5. STORE_STATE_I64 field
6. END_EXCLUSIVE_WRITE field
7. RETURN_VOID

Each emitted instruction also pins its typed virtual register definitions
and uses: r0 is the exclusive loan, r1 the loaded state i64, r2 the
argument i64, and r3 the checked-add i64. The store consumes r0 and r3;
loan release consumes r0. The verifier rejects changed operand identities,
use-before-definition, multiple definitions, wrong types, and unbalanced
loan scope. Every image additionally self-verifies exact opcode order,
field/argument binding and typed values. There is no
`await`, call, pointer, captured closure, alias escape, concurrent reentry,
unchecked arithmetic or implicit conversion instruction in this admitted
subset. This is an **instruction-level compiler MIR**, not a Java
implementation of Cart arithmetic or a physical state loan.

`Image.resolveCall` enforces nominal `VirtualActorRef[Cart]` typing, exact
method name and argument types, and emits a statically selected image-local
slot. It rejects `ActorRef[Cart]`, `ActivationRef[Cart]`, unrelated types,
unknown methods, wrong arity and wrong scalar types. Slots are not stable
cross-version ABI identifiers; hot upgrades must re-resolve them against a
validated code image and live generation fence.

`TypeChecker` now builds this MIR before intentionally raising
`VIRTUAL_ACTOR_MIR_UNSUPPORTED`. A positive MIR contract is **not**
permission for a guest program to execute, allocate actor state, use a live
reference, send data, mutate protected memory, or commit persistence.

## Next runtime gate — not implemented here

- Typed guest `VirtualActorRef[T]` values with domain, tenant, namespace,
  type and key (side-effect-free resolution)
- Compiler-owned exclusive state loans over actor-owned heap/stack and
  verified lowering of the checked i64 instruction stream into the real
  backend
- Single-flight lazy activation via the existing generation ledger; bounded
  typed mailbox admission and reply correlation
- Restore-before-publication, provider-issued epoch CAS, quarantine on failed
  restoration, STOP → physical quiescence → terminal CONTROL acknowledgment
- Exact-head stepful GraalVM/JUnit execution, actual Cart guest receipts,
  negative compiler cases, then AOT/backend conformance and independent review

No private source is authorized for cross-organization mirroring without
the independently approved source-transfer policy. An unstarted Actions job
is NOT_EXECUTED, not evidence.

## Tests

`VirtualActorPortableMirV1Test` contributes six authored JUnit methods:
exact instruction/loan shape, nominal call type rejections, deterministic
image-local slots, unsafe body mutations, forged lexical provenance and
production fail-closed behavior. The focused source-org workflow requires
real Surefire reports and the original protected MIR plus runtime parent
suites before a merge can be considered.

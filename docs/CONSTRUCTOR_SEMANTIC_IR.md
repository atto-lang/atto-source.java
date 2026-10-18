# Oreslang semantic IR: construction, callables, recursive aliases

This document defines the backend-neutral semantic objects for constructor and
callable/type checking. It is a language contract, not a Java AST ABI. Backends
may lower these objects differently, but they must preserve the same identities,
state transitions and rejection conditions.

## ConstructorDecl

A semantic constructor declaration has:

```text
ConstructorDecl {
  owner: ClassId
  receiver_binding: IMPLICIT | EXPLICIT_SELF
  user_parameters: Parameter[]
  effective_arity: u32
  body: CheckedBody

  // reserved for delegation work
  delegates_to: ConstructorKey?
}
```

`effective_arity == user_parameters.length`. The optional source token
`self` is a receiver binder and is never a user parameter.

The overload key is:

```text
ConstructorKey {
  owner: ClassId
  effective_arity: u32
}
```

Parameter types, generic substitutions, receiver spelling and backend ABI
parameters do not participate in constructor overload identity.

Constructors are not ordinary callable values and are never subtypes of
`AnyCallable`.

Actor declarations have no constructor semantic object at all. A parsed actor
constructor is rejected before overload analysis.

## ConstructionState

Each declaration-uninitialized instance field carries a saturated
assignment interval (for both immutable `val` and mutable `let`):

```text
AssignmentCount {
  min: 0 | 1 | 2+
  max: 0 | 1 | 2+
}
```

The useful states are:

```text
[0,0]  definitely uninitialized
[0,1]  maybe initialized
[1,1]  definitely initialized exactly once
[0,2+] maybe multiply initialized
[1,2+] initialized, possibly more than once
[2+,2+] definitely multiply initialized
```

A direct write maps `[a,b]` to
`[min(2+,a+1), min(2+,b+1)]`.

For a blank immutable `val`, a write is admitted only from `[0,0]`
and normal construction requires `[1,1]`. A blank mutable `let` must
have been assigned on every normal path but can be reassigned; its lower
interval bound must be at least `1` and its upper bound may be `2+`.
A construction region therefore carries the set of immutable tracked fields
separately from its assignment intervals. Joining states with different
immutable-field contracts is forbidden.

Control-flow join is:

```text
join([a,b], [c,d]) = [min(a,c), max(b,d)]
```

This is equivalent to MUST-set intersection plus MAY-set union, while preserving
an extensible representation for loop and callback cardinality work.

A semantic construction state also conceptually carries:

```text
ConstructionState {
  region: ConstructionRegionId
  phase: ALLOCATED | INITIALIZING | INITIALIZED | CONSTRUCTED
  fields: Map<FieldId, AssignmentCount>
  fixed_fields: Set<FieldId>  // val fields requiring exactly one write
  pending_cleanups: CleanupEdge[]
}
```

The current Java slice materializes the field lattice. The phase, region and
cleanup components remain explicit contract requirements for later CFG and
backend lowering.

`INITIALIZED` does not imply publication. The ordinary `Foo` capability is
created only at successful `CONSTRUCTED` completion. A construction receiver
must not escape or cross a scheduler/suspension boundary.

## Control-flow rules

Normal branch joins use the assignment interval join above.

A normally returning constructor edge must satisfy all required field states.
An abnormal edge that leaves construction does not need to initialize remaining
fields, but it still may not publish the partial receiver or read uninitialized
state.

RHS evaluation precedes the field write. Therefore:

```ores
self.x = self.x + 1;
```

reads `x` while it is still uninitialized.

A source `match` may contribute branch states only when exhaustiveness is
proven. The first Java implementation recognizes an explicit unguarded
catch-all (`_` or a binding pattern) locally; richer finite-domain
exhaustiveness remains owned by the general match checker.

Loop backedges, exceptional edges and deferred cleanups must be represented
explicitly before they may establish required-field initialization.
Pending cleanups are tracked in LIFO order: `completeCleanup(edge)` may
only discharge the newest registered edge. Publication is rejected until
all cleanups have been discharged and every required field is initialized. A backend
must fail closed rather than infer initialization from syntax alone.

## Construction suspension

Construction-scoped state may not survive a scheduler boundary.

The semantic checker rejects construction through at least:

- `await`;
- asynchronous iteration;
- blocking channel operations;
- blocking select/dynamic select;
- any future operation whose effect summary includes suspension.

This rule is independent of definite initialization. Even a field-complete
object remains unpublished until the constructor successfully completes.

## CallableType

A concrete callable semantic type is:

```text
CallableType {
  parameters: ParameterType[]
  result: Type
  effects: EffectSet
  capture_capabilities: CapabilitySet
}
```

Parameter source names are not part of callable identity.

`AnyCallable` is a sealed erased callable top type:

```text
ConcreteCallable <: AnyCallable <: Any
```

`AnyCallable` proves that the runtime value is a reifiable callable, but it
does not retain an invocation signature. Therefore an `AnyCallable` value
cannot be invoked until checked narrowing establishes a concrete
`CallableType`.

`Any` is the erased top type for ordinary **owned** runtime values. Borrow
capabilities are not subtypes of `Any`: a `Borrow<T>` cannot be widened to
`Any` and retained after its lexical lifetime. Concrete owned callables remain
ordinary values and therefore may widen to `AnyCallable` or `Any`.

Erasing to `AnyCallable` never erases capture, borrow, actor-isolation,
serialization, sharing or construction-region restrictions.

Recursive lexical callables have one additional semantic distinction. The runtime
may retain a pre-reserved self slot so the closure can call itself after the
binding is initialized. That slot is modeled as
`CaptureOrigin.RECURSIVE_SELF`: it is a runtime fixpoint cell, not an external
ownership capture. Ownership accounting therefore excludes it from outer
move/borrow capture effects, while runtime lowering preserves the slot identity.
It is never serializable, shareable authority, actor DATA, or supervisor
CONTROL.

A callable's capture metadata is descriptive of the values retained by its
environment; it is **not transport authorization**. In particular,
`CallableCapability.CAPTURES_SERIALIZABLE_DATA` means captured data satisfies an
independent serialization property. It never means the executable callable,
closure environment, code pointer, `AnyCallable` erasure, or callback ABI may
be encoded by `NetworkCodec`/`ActorMessageCodec`, admitted as actor DATA, or
used to construct supervisor CONTROL. The semantic IR therefore exposes
`serializableExecutableValue() == false` and
`actorDataAdmissible() == false` for every `CallableType`.

### Borrowed callable parameters

An immutable borrow of a concrete callable type is a non-escaping call-boundary
contract:

```ores
fnc invoke_now(borrow (() => void) cb): void {
    cb();
    return;
}
```

The callable signature is retained through the borrow, so `cb()` remains
statically typed. The callee may use the callable during the invocation but
cannot turn the borrow into an owned callable that outlives the call.

A freshly created inline lambda may be lent directly to an immutable borrowed
callable parameter without first naming it or spelling `rt borrow`:

```ores
invoke_now(|| -> {
    work();
    return;
});
```

This is an ephemeral borrow created by call checking, not a general implicit
borrow conversion. A named callable value remains owned and must use the normal
explicit borrow discipline when a borrowed callable parameter is required.

This contract is sufficient to admit a constructor closure that captures the
construction receiver:

```ores
type Runner = (borrow (() => void) callback) => void;

constructor(self, int value, Runner run) {
    run(|| -> {
        self.mutable_value = value;
        return;
    });
}
```

The closure remains inside the construction region. It may not publish
`self`, invoke unsafe instance dispatch, or suspend while the construction
borrow is live. An ordinary callable parameter such as `AnyCallable` or
`() => void` does not prove non-escape and therefore cannot receive such a
closure.

Nonescape does **not** imply invocation cardinality. A borrowed callback may be
called zero, one, or many times. Consequently it cannot establish the
exactly-once initialization proof for a blank constructor `val`; only directly
analyzable constructor control flow may do so in the current language.

Ordinary objects do not become callable by declaring a method named `call`.
Constructors do not become callable values. Instance methods do not silently
become bound callables; an explicit lambda makes receiver capture visible to
ownership analysis.

## RecursiveAliasType

A recursive alias gets stable nominal identity before its body is resolved:

```text
RecursiveAliasType {
  id: RecursiveTypeId
  generic_parameters: GenericParameter[]
  body: Type
}
```

Example:

```ores
type Callback = (Callback next) => void;
```

The recursive occurrence is a back-reference to the declared
`RecursiveTypeId`, never textual infinite expansion.

Recursive validation has two independent obligations:

1. **Representation recursion:** every runtime storage cycle must cross a
   representation indirection barrier such as a callable handle, `Ref`,
   `WeakRef`, pointer/box, or ordinary reference object.
2. **Type-instantiation recursion:** recursive generic references must be
   regular and finite. Initially, a recursive alias may recurse only with the
   same generic arguments.

Thus:

```ores
type Callback<T> = (T, Callback<T>) => void;   // regular
type Node = Ref<Node>;                         // indirect
type Inline = (int, Inline);                   // infinite layout: reject
type Strange<T> = (Strange<(T, T)>) => void;  // non-regular: reject
```

Direct unguarded alias cycles are rejected.

`typeof` remains an expression type query. Recursive aliases refer to
themselves by name; `typeof Alias` is not recursive-type syntax.

### Current Java implementation boundary

The Java compiler currently implements the non-generic, directly
self-referential callable case:

```ores
type Callback = (Callback next) => void;
```

and rejects direct alias cycles, mutually recursive aliases, and recursive
self-reference outside the callable guard. Generic regular recursion such as
`Callback<T>` is part of the semantic target above but is not yet claimed as
implemented by this PR. Backends must not infer support from this document
without a matching conformance fixture.

## Required diagnostic classes

Backend wording may differ, but the semantic failure category must be stable:

```text
CTOR_DUPLICATE_ARITY
CTOR_ACTOR_FORBIDDEN
CTOR_SELF_POSITION
CTOR_DELEGATION_POSITION
CTOR_DELEGATION_CYCLE
INIT_READ_BEFORE_WRITE
INIT_VAL_REASSIGN
INIT_MISSING_ON_SUCCESS
CONSTRUCTION_ESCAPE
CONSTRUCTION_SUSPEND
CONSTRUCTION_DISPATCH
CALL_ERASED_SIGNATURE
RECURSIVE_ALIAS_UNGUARDED
RECURSIVE_LAYOUT_INFINITE
RECURSIVE_GENERIC_NONREGULAR
```

Backend parity means identical source acceptance/rejection and observable
runtime behavior for conformance fixtures. Matching internal object layouts,
symbol names or machine-level hidden receiver parameters is not required.

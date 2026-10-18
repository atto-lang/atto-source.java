# Sibling constructor delegation — checked language contract

## Syntax and resolution

A normal class constructor may start with one `constructor(args...);`
statement. It is a **same-object sibling delegation**, not `new`,
not a regular function call, and not an `AnyCallable`. The syntax
is legal only as the first **top-level** statement of a class constructor.
Delegation nested in `if`, `match`, `try`, a loop, callback, lambda,
or cleanup is forbidden. Actor constructors continue to be forbidden.

The target is `(declaring class, number of explicit arguments)`.
Optional leading `self`, types, generic parameters, and backend ABI
parameters do not distinguish constructor overloads.

```ores
define class Counter as
    val int value;
    let int updates: 0;

    constructor(self) {
        constructor(40);
        self.updates = self.updates + 1;
    }
    constructor(self, int base) {
        constructor(base, 2);
        self.updates = self.updates + 10;
    }
    constructor(self, int base, int delta) {
        self.value = base + delta;
    }
end
```

The compiler verifies an acyclic delegation graph, detects missing arity
targets and type-checks the delegated arguments against the target's
parameter types. Delegation target visibility does not constrain internal
same-class delegation; the initially invoked constructor's visibility
is checked at the caller's boundary.

## Execution order

1. Evaluate the outer `new` arguments once, left to right.
2. Resolve and evaluate each delegation edge in order. Current Java
   implementation restricts delegated arguments to pure scalar literals,
   constructor scalar parameters, arithmetic + - *, unary + -, and
   conditional expressions composed of those; no `self`, capture,
   calls, allocations, borrow/move, suspension, or user effects.
3. Allocate **one** object, not one per constructor frame.
4. Evaluate declaration-site field initializers **once**, in declaration
   order, using the terminal initializing constructor's scalar parameters.
5. Execute the terminal constructor body.
6. On successful completion, unwind delegator *tails* from innermost
   to outermost, with the same unpublished `self`. A bare `return`
   completes only its current frame. The active frame's defers run
   according to normal block cleanup semantics.
7. Check required fields, then publish the object.

A failure aborts the chain and must not execute delegator tails.
A delegating tail may modify mutable fields but may not reinitialize
constructor-only `val` fields. A terminal constructor must definitely
initialize every blank `val` exactly once on every normal completion.

## Static proof

`ConstructorDelegation.validate` checks placement, argument purity,
target arity and cycle freedom. `ConstructorValInitialization` then
proves the terminal constructor's required fields and enters each
delegator tail with those fields already initialized. `TypeChecker`
checks target parameter types after resolving by arity;
`OwnershipChecker` checks argument ownership.

## Current fail-closed boundaries

- Delegation expressions with side effects or unknown effect summaries
  are rejected (even if otherwise semantically plausible).
- Recursive or conditional delegation is rejected.
- Actor constructors are rejected.
- This PR does not implement explicit base-constructor chaining,
  constructor-safe virtual dispatch, or a general exceptional/cleanup
  CFG for partially constructed owned resources.
- The JVM interpreter's preexisting constructor-only `self` lexical
  behavior remains unchanged; future receiver-binder tightening needs
  a compatible AST representation and migration plan.
- No backend parity is claimed without running the shared fixtures
  against an exact source tree. No CI failure with empty job steps
  counts as executable compiler/test evidence.

Required shared diagnostics: `CTOR_DELEGATION_POSITION`,
`CTOR_DELEGATION_CYCLE`, `CTOR_DELEGATION_TARGET`,
`CTOR_DELEGATION_ARG_EFFECT` and `CTOR_DELEGATION_ARG_TYPE`.

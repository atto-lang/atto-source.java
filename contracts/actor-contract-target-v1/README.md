# Actor-targeted contracts, nominal Java stage 2

**All source/test cases UNEXECUTED.** This stage adds
`define contract FooContract for actor as mtd abc(): void; end`.
An actor may declare `implements FooContract`, and its *existing*
public actor methods must fulfill the required name+arity signatures.

The positive fixture contains the contract plus an actor declaration
in the **same source unit**, using existing mailbox-dispatched actor
methods. The negative fixture omits `xyz` and must fail type checking.

An actor contract is not a module contract, namespace contract, ordinary
class interface, free function, actor reference, runtime singleton,
`ActorContext`, or state capability. A nominal actor `implements`
does not allow a caller to access private actor memory. The compiler
reuses existing class/interface method shape checking, but additionally
requires **directly declared public, synchronous, nonstatic actor
methods** and rejects inherited ordinary-class methods, abstract slots,
explicit-receiver lowering without proof, suspension/async/effects,
reserved `receive`, `tear_down`, `handle_state`, and CONTROL
`on_*` hooks. Public `tear_down` is rejected outright in both
source and typed-AST admission, and private synchronous teardown remains
available to the supervisor. `on_stop` and `on_restart` are forbidden
as user-defined actor methods until dedicated CONTROL lowering exists.
It also rejects ordinary class implementations and unsupported generic
and inherited actor contracts.

Foreign-file actor-contract linking, namespace-level actor contract
requirements, and executable external `define mtd (Foo foo) abc():void`
are **not yet implemented**. Their safe implementation requires
explicit nominal provenance, method slot binding, execution authority
from the actor mailbox, nonreentrant dispatch, generation fencing,
ownership, cancellation and deterministic cleanup. No reflection
or arbitrary external actor-state access.

The audit adds 22 authored JUnit cases overall, including direct
public synchronous actor witness requirements, reserved method-slot
rejection, public teardown and CONTROL-hook rejection, and preserving
private synchronous teardown for supervisor-owned cleanup.

Required proof: exact-head Git tree, Java/JUnit runs with nonzero
executed steps, real guest invocation for accepted fixture and
compile-time negative diagnostic for rejected fixture, independent
review, and explicit parity matrix across Java, LLVM, .NET, Wasm and
Forge. Zero-step Actions is no evidence.

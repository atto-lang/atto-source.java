# Actor and namespace callable boundaries

Actor classes/instances, ActorRef routing handles, module descriptors and class
namespace objects are not first-class **ordinary callable arguments**. An
ordinary fnc, routine, constructor, method or lambda cannot receive these,
including through recognized nested data or imported aliases. Use static
members directly and `ActorRef.send(message)` for mailbox routing.

Ordinary non-actor class instances (such as `new Box()`) are legitimate data
arguments and must not be confused with a class namespace.

## Runtime ownership operations

- `rt borrow x`: temporary immutable borrow under lexical lifetime checking.
- `rt borrow mut x`: exclusive mutable borrow, no cross-suspension escape.
- `rt take x`: consumes a named owner and invalidates it, even for scalar
  bindings. Projected moves (`rt take object.field`, `rt take array[i]`)
  reject until source-slot invalidation/rollback is correctly lowered.
- `rt copy x`: detached mutable deep copy of supported owned scalar,
  record, array, Option/Result, collection and ordinary class graphs.
- `rt share x`: detached, deeply readonly `Readonly<T>` projection of
  supported safe graph shapes. Nested containers carry immutable provenance,
  and readonly class objects must remain readonly across async detachment.

Cyclic graphs, unverified host types, actor identities, namespace values,
executable capabilities and unsupported cross-boundary ownership fail closed.

The compiler validates source arguments and imported call signatures where
statically available; interpreter admission independently rejects actual
actor/namespace values. Dynamic host interop and unknown imported-module field
contracts remain audit/verification targets. This is a draft contract, not a
whole-language formal proof; JVM exact-head tests must actually execute.

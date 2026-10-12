# Built-in actor superclasses and user actor specialization

**Initial implementation (2026-10-10):** Oreslang parses a builtin
`from serial actor` superclass and enforces its strict scheduling
contract. It is *not* a class named `serial` or a normal superclass
lookup. The compiler preserves the base-kind metadata in the AST,
including annotation expansion and tree shaking. The runtime retains
an independent single native execution lease and serial receive
admission across suspension.

## Source syntax

```oreslang
define actor CriticalWorker from serial actor as {
    on_start(): void {
        return;
    }

    receive(ActorMail<String> mail): void {
        // This is user-defined behavior; the builtin scheduler owns
        // ready/done, mailbox admission, cancellation and execution.
        self.end();
        return;
    }
}
```

An equivalent body can be closed by `end`:

```oreslang
define actor CriticalWorker from serial actor as
    receive(ActorMail<String> mail): void {
        self.end();
        return;
    }
end
```

Both forms have `ActorKind.SHARED`,
`BuiltinActorBase.SERIAL`, and compile-time
`ActorConcurrencySpec("actor", 1, 1)`. A derived actor may also
use ordinary named actor inheritance:

```oreslang
define actor SpecializedWorker extends CriticalWorker as
    receive(ActorMail<String> mail): void {
        self.end();
        return;
    }
end
```

The existing `extends BaseActor` declaration supplies ordinary
inherited actor fields/methods and dynamic dispatch. The builtin
`from serial actor` clause supplies scheduling and lifecycle
guarantees instead. Do not confuse the two mechanisms.

The `serial` declaration prefix and `from serial actor` must not
both be specified on the same actor. The former is shorthand for the
same strict admission policy; the latter also records an explicit
builtin superclass contract.

## Non-final versus final behavior

The only currently supported builtin superclass is `serial actor`.

- **Overridable hooks:** `on_start`, `receive`, and user-defined
  methods in named actor parents, subject to normal signatures and
  accessibility rules. The runtime invokes the most-specific
  applicable receive implementation using existing actor inheritance.
- **Final runtime responsibilities:** mail delivery, supervision,
  actor identity, termination, cancellation, ready/done and output
  access, end and signal methods. These operations are installed by the
  runtime and may not be overridden or shadowed by subclass members.
- **Inherited actor kind:** SHARED is enforced. The runtime and compiler
  reject attempts to weaken serial concurrency or change the isolation
  kind through an inheritance declaration.
- **Native execution parallelism:** at most one native thread executing
  code belonging to an actor at a time, regardless of actor subtype.
  `await` or `rt cooperate` releases the carrier; strict serial
  admission retains the logical handler turn across suspension.

The first release reserves and enforces builtin *final names*. A
user-defined `final` modifier for application actor methods has not
yet been specified. Do not interpret an arbitrary app method as final
without an explicit corresponding type-system contract.

## Future extensibility

Additional built-in subclasses, including protocol-specific
`http actor`, `tcp actor`, `websocket actor`, distributed
`ProxyActor`, or user-defined actor kinds, require an explicit
**ActorKind policy interface**. That interface should separate
request/message admission, native execution lease, isolation,
mailbox transport, serialization policy, supervision, and
remote identity. Inheriting a custom actor kind must never grant
the subclass the ability to override security controls or
scheduler-enforced lifecycle methods.

A subclass changes behavior, not the invariants enforced by
the runtime. Changing a parent's request/message limits requires
a compiler-validated capability to do so and a matching
scheduler implementation—not simply redefining a mutable field.

## Validation

Regression tests cover brace and end syntax, AST identity,
compile-time final-method/field rejection, transitive actor
inheritance, invalid isolation/limit overrides, and a source-level
spawn/cooperate test. The tests are part of the source tree's
Maven verification and exact-tree CI process.

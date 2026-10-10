# Identity-dependent return contracts

An identity-dependent return annotation, such as `fnc decorate(T v): v`,
requires every normal return path to produce **the original instance passed
as `v`**. It is not an ordinary nominal `T` return and does not imply that
the object was mutated.

```oreslang
pub fnc decorate(T mut v): v {
  v.foo = "bar";        // valid: mutate referent
  return v;             // valid: exact original reference
}

pub fnc replace(T v): v {
  return new T();       // compile error: identity return mismatch
}

pub fnc rebind(T mut v): v {
  v = new T();          // compile error: identity return rebind
  return v;
}

define class Builder as
  pub same(): this {
    return self;         // valid: 'this' names the receiver identity
  }
end
```

`: self` retains the older receiver-polymorphic type meaning; it does
**not** make the exact-instance promise of `: this`.

Immutable local aliases and calls to statically identified, already-checked
identity-returning functions can preserve identity transitively.
Mutable parameters may update fields but cannot rebind the original value.
Overrides must retain an ancestor's exact-identity guarantee. An unknown
callable or a nominally compatible replacement cannot manufacture proof.

Callable types preserve provenance even when checking structural method shapes
and first-class function values. A return contract carries either the original
receiver (`: this`) or a zero-based parameter position (`: v`, based on the
parameter's slot, not its name). An identity-preserving callable may be used
where only the nominal return type is required, but the reverse conversion is
not sound. Different identity-source slots are never interchangeable.

Discarding `do select` arms do not return from the enclosing callable, but
their captured expressions can still execute. Captured identity parameters
cannot be rebound within those arms, including nested control flow or `defer`.
Contract checking is performed before executable admission, alongside type
checking and ownership checking. Type compatibility, mutable capabilities,
borrows and lifetime rules remain independent checks.

Current conservative limitations: async, generator, actor and abstract
identity-returning callables are rejected until their suspension and virtual
dispatch semantics are covered. Dynamic method calls are not currently
accepted as identity *forwarding* proof. In a future iteration, full
effect/alias dataflow should allow richer expression forms while preserving
this fail-closed guarantee.

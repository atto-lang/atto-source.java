# Identity-dependent return contracts

An identity-dependent return annotation, such as `fnc decorate(T v): v`,
requires every normal return path to produce **the original instance passed
as `v`**. It is not an ordinary nominal `T` return and does not imply that
the object was mutated.

```oreslang
pub fnc decorate(T v): v {
  return v;             // valid
}

pub fnc replace(T v): v {
  return new T();       // compile error: identity return mismatch
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
identity-returning functions can preserve identity transitively. An unknown
callable or a nominally compatible replacement cannot manufacture proof.
Contract checking is performed before executable admission, alongside type
checking and ownership checking. Type compatibility, mutable capabilities,
borrows and lifetime rules remain independent checks.

Current conservative limitations: async, generator, actor and abstract
identity-returning callables are rejected until their suspension and virtual
dispatch semantics are covered. Dynamic method calls are not currently
accepted as identity *forwarding* proof. In a future iteration, full
effect/alias dataflow should allow richer expression forms while preserving
this fail-closed guarantee.

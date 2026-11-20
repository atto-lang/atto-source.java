# Implicit receiver method calls — first compiler slice

An unqualified direct call `method(args)` in an instance method body is
eligible for lexical receiver binding as `self.method(args)`. This is **not**
a new runtime search rule; it selects a compiler-known instance method slot.
It does not create a first-class bound method or a new ambient capability.

## Resolution and security

1. Resolve lexical values/parameters first. If a local has the same name, its
   callable type determines validity; it never falls through to a `self` method.
2. Preserve compiler builtins, lifecycle hooks, and special names. For these,
   use explicit `self.name(...)` to distinguish a user method.
3. Only when the current callable has a valid lexical `self` and an instance
   method exists at the **exact explicit argument arity**, consider implicit
   instance dispatch. Static methods, free functions and non-lexical closures
   never gain an implicit receiver.
4. If a free/imported function of that name and arity also exists, reject as
   ambiguous and require `self.name(...)` or fully qualified free-call syntax.
   Do not rely on declaration order, parameter types, return types, dynamic
   receiver methods, or accidental import priority.
5. Typecheck through the existing explicit member-call path, preserving
   visibility, inherited slots, generics, effects, suspension, ownership and
   protected receiver constraints. At runtime, use the compiled lexical slot
   and actual receiver's checked virtual dispatch table.
6. Never infer `self` for a bare method *value* or across a suspension/closure
   capture boundary that would otherwise forbid borrowing `self`.

## Actor state boundary

`handle_state(...)` is an eventual special case of **compiler-owned** actor
state access, not a user-declarable method. Its unqualified spelling must be
equivalent to `self.handle_state(...)` only once the compiler has built the
sealed `state { ... }` AST, critical read/write loan checker, non-escape rules,
actor-turn lock/ticket enforcement, and native/runtime lowering.

**Current status:** the Java parser rejects `state { ... }` and user-declared
`handle_state`. This change intentionally does not lift that rejection.
No call resolver may synthesize a state capability or pretend a callback has
an exclusive loan without the runtime implementation.

## Cross-backend verification contract

- Java/Graal: run the `ImplicitSelfMethodLookupTest` `.atto` fixtures, including
  virtual dispatch, actor-local helper, lexical shadowing, static rejection and
  free-call ambiguity; prove full guest execution, not parser-only success.
- LLVM: run `native_source_class_methods` with real compiler+clang execution,
  sanitizer mode, and differential reference cases. LLVM's current scalar
  class subset does not provide inheritance or sealed actor state.
- Wasm, Forge/Cranelift, and .NET: treat conformance as **NOT VERIFIED** until
  their real compilers execute the same positive/negative cases.
- A CI record containing no executed steps is **NOT EXECUTED**, not a pass.

Both spellings must resolve to the same typed method identity and receiver
authority. An error must occur before executable IR emission when lookup is
ambiguous or the receiver/state capability is unavailable.

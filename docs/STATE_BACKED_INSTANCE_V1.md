# First executable Java instance-state backing slice

**Scope: experimental Java/GraalVM only; unverified until exact-head tests execute.**

Supported source:
```oreslang
@StateBacked(MutableStateContainer(scope = #instance))
define class InventoryState as
  let int available = 10;
  pub available_count(): int { return self.available; }
end
```

The annotation is compiler-validated and retained in `Ast.ClassDecl.annotations`.
The **class object itself owns the fields**: it is not a separate injected
storage provider, hidden global, proxy, reader/writer lock or actor-owned arena.
No heap placement difference is inferred from syntax. The existing Java class
implementation and type/borrow checker remain responsible for storage and
exclusive mutable access. Per-call injected `borrow`/`borrow mut` parameters
are ordinary lexical Oreslang borrows, **not** the `@Inject` annotation.

V1 intentionally refuses `#shared`, `#actor`, `#task`, singleton scopes,
unrecognized or missing scopes, multiple class annotations, empty backing
classes, public representation fields, abstract classes, inherited classes and
static methods. `@Inject` still fails closed; no userland constructor
argument may forge a state-provider identity.

Test target:

```sh
mvn -q -Dtest=StateContainerInjectionAdmissionTest,StateBackedOwnedInstanceV1Test test
```

The committed `examples/state-backed-instance-v1.ores` intentionally
exercises two independently allocated objects, one successful reservation and
three rejected reservations. Exact expected stdout:
`7|3|10|true|false|false|false`.

Before calling this executable feature *verified*, require a nonzero-step
exact-head JDK25/GraalVM test run, genuine guest execution, immutable source
SHA, and borrow/type diagnostics. After this, implement the independent
`@Inject(ExistingProvider)` container resolver and typed state regions,
followed by reader/writer scopes and lifecycle pins. Native LLVM, WASM,
Cranelift and .NET must run identical source rather than assuming this Java
class lowering proves cross-backend parity.

Do not merge this feature under a full mutable state-container parity claim.

# Namespace-attached actor state (static admission candidate)

This corpus is `Ast.ActorStateDecl` **parser / nominal binder input only**.
It is **not** executable actor state and does not grant a critical section.\n\nThe canonical non-inline declaration is `declare actor Foo;` (or `pub declare actor Foo;` for public methods). `define actor Foo;` is rejected: `define actor Foo as ... end` remains the separate inline implementation form.

- `actor.atto` declares nominal `foo.Foo` with `pub declare actor Foo;` and supplies an out-of-line external receiver method.
- `state.atto` attaches a sealed private `int x: 5` schema to exactly
  that nominal actor in the same source namespace.
- `reject_wrong_namespace.atto` cannot attach `other.Foo` to `foo.Foo`.
- `reject_unguarded_return.atto` illustrates forbidden direct state
  projection; the current complete guest program must fail closed at
  `ATTACHED_ACTOR_STATE_CRITICAL_LOWERING_UNSUPPORTED` or an earlier
  actor-execution gate, not execute or return state.

`ActorStateBinder.bindSourceSet` is immutable static-only binding keyed by
(package identity string, namespace, actor name). Its caller-provided source
map is NOT cryptographic package provenance. Connect it to the manifest-locked
source inventory before issuing any code generation proof.

V1 only accepts distinct private `let int field: signed_i64_literal;`
fields, each initialized once per activation, with no method/legacy-field
collision, computed initializer, reference, generic/inherited actor or
public state declaration. The 128-field limit is a parser/AST bound, not
a physical memory allocation guarantee.

**Critical semantics still to implement:** compile an actor-owned
`critical-read` loan for inspection and an exclusive `critical-write`
loan for mutation, with nonescape/no-await checks, generation-fencing,
cancellation/restart/STOP cleanup, and no supervisor raw state authority.
The proposed `critical mtd` surface is not admitted by this source slice.
No ordinary actor method may read or mutate the schema in guest execution.

The focused JUnit workflow runs
`NamespaceActorStateAdmissionTest` plus existing actor receiver suites
at the immutable PR head. JUnit suites and fixtures are AUTHORED, but
**NOT_EXECUTED** until a nonzero-step source runner produces receipts.

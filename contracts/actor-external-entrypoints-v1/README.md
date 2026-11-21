# Explicit actor external ingress (static stage)

AttoLang namespace-actor entrypoint spelling:

```atto
namespace foo;
declare pub actor Foo implements ActorXYZ;
external mtd (Foo f) init(): void { return; }
external mtd (Foo f) receive(ActorMail<int> mail): void { return; }
pub mtd (Foo f) bar(): void { return; }
```

`external` is a contextual actor method entrypoint marker, independent of
`pub` visibility. Receiver-first and legacy name-first method forms retain
the same nominal actor/arity selector. The parser rejects `init` or
`receive` without an explicit `external`; AST stores the proof bit and
the static binder independently rejects a fabricated unmarked AST.

`init`: private, zero value parameters, return void; actor activation only,
exactly once per activation before READY publication. `receive`: private,
one `ActorMail<T>`, return void; mailbox delivery only. These are the desired
runtime restrictions, **not evidence of runtime implementation**.

`pub external` can mark other actor methods for future authorized remote
dispatch, but does not grant direct actor state access or bypass borrow,
ownership, actor generation, or supervisor fencing.

This is a deliberately **static-only, non-executable** milestone on the
receiver-first namespace actor draft. TypeChecker continues to fail closed
until compiler-sealed actor context, spawn/mailbox ingress lowering and
cross-backend AOT/guest execution are proven. Legacy actor-body `receive`
remains accepted for migration compatibility, so the project-wide strict
requirement cannot be considered complete yet.

The test suite `ActorExternalEntrypointTest` covers positive parser/AST,
negative parser/binder shapes, duplicate selectors, fabricated AST rejection,
cross-file namespace binding and execution denial. Run exact-head Maven
JUnit with nonzero steps; source code alone is **NOT EXECUTED** evidence.

## Follow-up static-boundary hardening

An `Ast.ExternalActorMethodDecl.runtimeExternal` boolean is **untrusted
declaration metadata**, never an actor-context capability or an unforgeable
authorization proof. A tool can construct that AST with `true`: TypeChecker
must still deny execution until compiler-issued actor turn/dispatch authority
exists. The binder is deliberately not a runtime access-control layer.

The binder rechecks argument names, duplicate parameters, receiver shadowing,
structural/typeless values and the exact private mailbox/lifecycle signature
even for constructed ASTs. A receive binding is not mutable and its nested
payload type syntax rejects inferred, borrow, callable and void shapes with a
256-node iterative admission limit. These checks are conservative front-end
shape gates, not a transitive borrow checker or proof that user-defined types
are safely transferable. Real `ActorMail<T>` identity must be resolved to a
compiler-owned nominal type before any guest execution.

Two additional cross-file negative tests assert that `external init` cannot
be redefined in another compilation unit and `external receive` cannot be
overloaded based on different `ActorMail<T>` payload types. Both use the
original multi-source namespace binder, not a source-text regex. These tests
must actually run before being counted as evidence.

The canonical actor form is `declare pub actor Foo;` (optionally
`implements ActorXYZ`). This is distinct from the `declare` forward-type
syntax and from the legacy `pub define actor Foo as f;` compatibility form.
Its AST uses a non-sourceable internal receiver sentinel to preserve nominal
binding without introducing a guest-visible actor variable. Trait names remain
unresolved syntax: no conformance proof, state authority, dispatch, or runtime
actor instance is created until the executable backend implements those gates.

The canonical syntax is also exercised by
`contracts/actor-external-entrypoints-v1/declared_actor.atto`, and its
rejected alias spelling by `reject_declared_actor_alias.atto`. The Java
JUnit parser/binder test reads both actual `.atto` files. Neither fixture is
a passing guest execution or trait-conformance certificate.

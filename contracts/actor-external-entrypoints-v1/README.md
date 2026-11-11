# Explicit actor external ingress (static stage)

AttoLang namespace-actor entrypoint spelling:

```atto
namespace foo;
pub define actor Foo as f;
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

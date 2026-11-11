# AttoLang `with` immutable field updates: ownership and compiler contract v1

Status: **P0 grammar and AST only** on the Java compiler branch. **Not executable**.
The Java type checker, ownership checker, and evaluator intentionally reject updates
until type-directed reconstruction, object layout and all required ownership
proofs are implemented and tested. No other backend is claimed to support this.

## Syntax and explicit return

```atto
define class Point as
    x: int;
    y: int;

    incr_x(): Point {
        return rt copy self with { x => self.x + 1 };
    }
end
```

AttoLang's current canonical instance receiver is `self`. `this` as an alias
is not admitted by this feature. `return` is **always explicit**; `with`
produces a value, never an implicit callable return.

Grammar:

```text
with_update := unary_expression ("with" "{" field_update ("," field_update)* ","? "}")*
field_update := identifier "=>" expression
```

`with` is contextual; `with(foo)` may still be an identifier call. Nested paths,
index updates, method setters, empty updates and overriding one field twice are
not admitted by the P0 parser. Binding power is weaker than unary `rt copy`
and `rt take`, but stronger than multiplication: `rt copy p with {...}`
means `(rt copy p) with {...}`, *not* `rt copy (p with {...})`.

## Intended ownership behavior (requires P1–P3 implementation)

| Source | Meaning | Valid only if |
| --- | --- | --- |
| `rt copy p with {x => v}` | Produce independent updated value, preserve `p` | `rt copy` has a verified Copy/copy-contract implementation |
| `(rt take p) with {x => v}` | Consume `p`, reconstruct updated value | No live loans, exclusive ownership and valid field moves |
| `p with {x => v}` | Preserve `p` | Compiler proves `Copy`; otherwise error |
| `rt borrow p with {...}` | Not an owned receiver | **Always error** |
| `actor_ref with {...}` | Would bypass actor-state admission | **Always error** |

Do **not** introduce implicit heap deep clones, copy elision that changes
identity, or an untyped `clone` fallback. Copying user-defined classes needs
a statically resolved copy contract, and copying an owning handle must never
duplicate its exclusive release authority. The current Java ownership checker
already refuses `rt copy` on move-only values pending the copy-contract
lowering. This restriction must remain in force.

For a consumed receiver, unchanged non-Copy fields may move into the new
value exactly once, subject to drop/panic/exception-safe cleanup. Field
replacements must be checked for type, visibility, readonly capability,
ownership, generic substitution and any constructor invariants. If an old
field is replaced, cleanup/drop belongs to the old owning value, once only.
Reconstruction must not publish a partially initialized object or bypass
class validation. Methods and inherited layouts require statically proven
field views; this is not dynamic dictionary assignment.

## Evaluation and effects

* Evaluate the receiver exactly once.
* Each field expression evaluates exactly once, in source order.
* RHS reads of `self.x` or `p.x` see the **original** value (a frozen logical
  snapshot), not earlier replacements.
* All RHS values must be computed and type/ownership checked before the
  updated result is published. Failure must not leave an externally visible
  partially mutated instance. If the receiver is consumed, unwind and drop
  obligations must be verified before enabling an in-place optimization.
* Returning a borrow, guard, actor self-capability or an unverified shared
  mutable alias from `with` is forbidden.
* `await`, `select`, and other suspension points in RHS require proof that
  receiver loans survive across suspension; default to rejecting them.
* In-place lowering is only a permitted optimization under non-observable
  identity, uniqueness, exception-safety and alias proofs. The syntax does
  **not** promise zero allocations.

## Compiler rollout and evidence

- **P0 (this branch):** contextual grammar, `Ast.WithExpr` with distinct
  field replacements and deterministic order, Java parser boundary tests,
  explicit checker/evaluator fail-closed errors. No success fixtures execute.
- **P1:** implement checked `Copy` contract (or certified structural Copy)
  with copy ownership proof; reject non-Copy plain receivers, borrows and
  actor state; add positive/negative TypeChecker and OwnershipChecker tests.
- **P2:** implement immutable value reconstruction for records/structs and
  nominal classes, preserving object layout, constructor invariants, drop
  semantics, inheritance and RHS snapshot. Execute positive guest `.atto`
  fixtures on the Java interpreter and GraalVM Native Image.
- **P3:** publish the portable lowering contract and execute *identical*
  conformance fixtures in LLVM, .NET AOT, Wasm, Forge and Java; report each
  backend and SHA separately. An AST parse check is not backend parity.

Minimum negative tests: move-only receiver without `rt take`; `rt copy`
without a checked implementation; live mutable/immutable loans during
move; copy of owning resource; partial move; unauthorized private/final
field; actor sealed state; unknown fields; duplicate fields; wrong field
type; borrow-typed replacement; RHS exception cleanup; RHS suspension;
self-return without explicit `return`; and alias visibility after update.

**Execution evidence:** GitHub-hosted tests not yet confirmed at creation time.
The JUnit parser/AST checks are not a substitute for compiler-checked
execution of guest `.atto` source.

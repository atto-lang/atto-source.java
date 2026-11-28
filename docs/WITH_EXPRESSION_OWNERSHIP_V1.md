# AttoLang immutable `with` updates: ownership and executable contract v1

Status: **Java/Truffle executable, narrowly gated** (draft PR #2832).
Parser, type checker, ownership checker, evaluator and actual guest `.atto`
fixtures are implemented for (1) readonly structural records whose complete
layout is statically `Copy`, and (2) fully immutable, ctorless, non-generic,
non-inherited, non-actor classes of primitive scalar fields under **explicit**
`rt copy` or `rt take`. This does **not** certify full class, struct or
multi-backend support.

## Canonical AttoLang syntax and explicit returns

```atto
define class Point as
    pub final int x: 10;
    pub final int y: 20;

    pub incr_x(): Point {
        return rt copy self with { x => self.x + 1 };
    }
end

pub routine main(): void {
    const Point point = new Point();
    const Point next = point.incr_x();
    stdio.stdout.write(point.x);
    stdio.stdout.write(next.x);
    return;
}
```

The instance receiver is always `self`, **never `this`** as a special
receiver alias. `return` is **explicit**: `with` returns an expression
value but never returns implicitly from its containing callable.

Grammar:

```text
with_update := unary_expression ("with" "{" field_update ("," field_update)* ","? "}")*
field_update := identifier "=>" expression
```

`with` is contextual, not a globally reserved word; `with(foo)` remains a
callable reference. Only direct statically named fields are supported. Empty
updates, duplicates, nested paths, index paths and setter dispatch are not
admitted. Operator precedence is intentional: `rt copy p with {...}`
means `(rt copy p) with {...}`, not `rt copy (p with {...})`.

## Ownership rules

| Source | Meaning | Current Java v1 admission |
| --- | --- | --- |
| `rt copy record with {x => v}` | Preserve original record; build a fresh readonly result | Yes when every field is certified `Copy` |
| `record with {x => v}` | Implicit value copy | Yes for certified readonly `Copy` records only |
| `(rt take record) with {x => v}` | Consume source binding, reconstruct result | Yes for certified readonly `Copy` records |
| `rt copy self with {x => v}` | Fresh independent nominal object | Yes for eligible immutable scalar-field product classes |
| `rt copy object with {x => v}` | Same, from a non-actor class binding | Yes for eligible immutable scalar-field product classes |
| `(rt take object) with {x => v}` | Move source binding into new nominal value | Yes for eligible immutable scalar-field product classes |
| `object with {x => v}` | Implicit class copy | **Rejected**; classes remain move-only by default |
| `rt borrow value with {...}` | A borrow is not an owned receiver | **Rejected** |
| actor-owned `self`, mutable class/struct, class with constructor, inherited or generic class | Unsafe or unproven reconstruction | **Rejected** |

The nominal derived copy contract has a deliberately narrow static proof:
no constructor, inheritance, generics, abstract class or actor semantics;
at least one statically declared field; each field must be non-rebindable
(`val`, `const`, `final` equivalent slot policy), non-mutable referent, and
a non-borrowed builtin `int`, `bool`, `float`, `string` or `String` type.
Shadowing these builtin names with a local nominal type **or type alias** disqualifies the
class; the type checker also proves their resolved types are builtin scalars. This explicit `rt copy` creates a new object, **not** an implicit
`Copy` trait for arbitrary nominal values. Do not extend it to an
owning handle, ordinary heap resource, mutex guard or actor state.

The compiler ownership pass enforces use-after-take and borrow invalidation.
The runtime independently verifies eligible nominal layout and initialized
fields before constructing another class object. No actor-state authority
is introduced.

## Evaluation, isolation and effects

* Evaluate the receiver once, then replacement RHS expressions once each,
  left-to-right. They read the original receiver, not previous replacements.
* Validate field existence, lexical visibility, type, readonly capability and
  ownership. No duplicate field names and no computed/dynamic setter path.
* Assemble a fresh readonly record or class object **after** RHS evaluation
  completes; never mutate a published original. Failed RHS computation must
  not partially publish a value.
* V1 restricts replacement RHSs **and receiver sources** to
  conservatively non-suspending, effect-safe expressions.
  `rt copy`/`rt take` wrappers and nested `with` updates are traversed,
  preventing them from hiding `await`, unverified calls, channel operations
  or assignments. The guest evaluator's source-task scanner independently
  traverses all `WithExpr` children. Full effect and exception-path
  summaries remain future work.
* No allocation-free guarantee. The Java interpreter currently constructs
  fresh nominal objects for `rt copy` and `with`; eliminating redundant
  copies requires independently verified uniqueness and alias proofs.
* Guest `.atto` programs are actual fixture files under
  `src/test/resources/with/`, not embedded substitutes.

## CI evidence and limits

- **PASS:** [private exact-source-subset runner #42958096906](https://github.com/voxletra/voxletra-e2e/actions/runs/42958096906)
  compiled 258 Java source files and 2 selected JUnit classes with Java 21,
  executed 19 JUnit tests with 0 failures, errors or skips, and verified all
  348 mirrored Git blob hashes before running.
- **Source PR head:** `6bf579d6d2fbed3a764ef96cf2e876eaa4451875`
  at this checkpoint. Exact changed feature sources/fixtures are mirrored;
  the carrier's remaining files derive from the explicitly disclosed
  previous subset. This is **not** an exact full-source-tree build.
- **Audit coverage:** 23 real guest `.atto` fixtures cover unchanged
  original values, original-RHS evaluation order, aliased type names, private
  fields, inherited classes, effects in receiver and RHS, borrowed `self`,
  live-loan moves, move-only resources and mutable classes/structs.
- **Carrier scope:** exactly 30 feature-related files match source commit Git
  blob SHAs. The source Git tree contains 1,910 blobs; the carrier mirrors a
  verified 348-file subset only. A complete source HEAD build is **not** claimed.
- **NOT EXECUTED:** GraalVM Native Image AOT, LLVM, .NET AOT, Wasm, Forge,
  full release/conformance matrix, sanitizer matrix and formal ownership
  proof. Source-org GitHub Actions jobs failed before running steps; their
  red status is not evidence about the compiler feature.

## Required next milestones

1. Add independent negative fixtures for private access, mixed ownership
   fields, guard/borrow lifetime, class invariants and arbitrary non-Copy
   classes, plus property tests for immutable alias preservation.
2. Add true field-wise affine reconstruction for mutable structs and
   move-only class fields only after exception-safe drop/cleanup and
   per-field ownership receipts exist; never silently deep-clone a heap.
3. Generalize nominal copying using an explicit user-authored copy contract
   with audited effects and layout, rather than broadening the derived
   scalar class eligibility.
4. Publish an exact portable MIR/HIR `WithExpr` contract and execute
   identical `.atto` fixtures on all supported backends, including full AOT.
5. Ensure the formatter round-trips `self`, `rt copy`, `rt take`,
   canonical `with`, and explicit `return` without changing precedence.

Leave draft PR #2832 unmerged until broader safety and compiler evidence is
reviewed. Passing Java guest fixtures does **not** establish cross-backend
parity or generalized ownership-safe class copying.

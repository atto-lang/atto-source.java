# Binding, referent mutability and transport conformance

Oreslang's proposed canonical contract, agreed 2026-10-06. Code is normative only after compiler, borrow checker, runtime and tests converge.

| Declaration | Reassign name | Mutate referent |
| --- | --- | --- |
| `const x` | no | no |
| `val x` = `const mut x` | no | yes if ownership permits |
| `let x` | yes | no |
| `let mut x` | yes | yes if ownership permits |

In select cases, `case job = readch(jobs) -> { ... }` defaults to immutable/read-only. `case val job` fixes binding but permits referent mutation; `case mut job` is shorthand for `case let mut job`. Only the winning case receives ownership. Existing `case readch channel: val job { ... }` may be accepted for compatibility.

`mut` is not a bypass around borrowing: a live immutable borrow excludes mutation; only one mutable borrow may exist at once; a moved owner is unusable; nested fields, indexes, collection methods, closure captures, deferred continuations, and actor access paths must be checked transitively. Mutation through a readonly view is rejected, even if another alias may have write permission at other times. Use canonical `rt borrow`, `rt copy`, `rt take`, `rt share`, never raw pointer operators as public syntax.

**Transport:** actor mailbox messages must be serialized/frozen according to boundary policy. They cannot contain executable functions, raw object pointers, live borrows, or shared mutable graphs. An ordinary local `Channel<T>` can eventually transport closures and object references only when restricted to a provably safe execution domain and ownership/lifetime rules. A local channel must never bridge actor domains to circumvent the mailbox. The present compiler intentionally blocks callable `Channel<T>` payloads; relax only with explicit local/mailing mode separation and conformance tests.

**Inferred struct:** canonical proposed spelling is `struct infer{foo: "bar"}` (statically inferred shape), distinct from dynamic `obj{...}`/dynamic maps. This is a design requirement, not evidence of implementation.

Test matrix: all four declaration modes; const initialization with runtime expressions; field assignments and mutating methods at multiple depths; aliasing, moves, borrow lifetimes and await; select-case binding/losing cases; local channel functions and captures; recursively rejecting functions/pointers through serialized mailboxes.

# Option safe field access and lazy fallback (initial Java slice)

**Status:** Draft implementation on Java/GraalVM; not yet validated by executable CI and
**not** a claim of Java/LLVM/native-image/source-language parity. Tracks common-source #152.

## Operators

- `opt?.field` requires `opt: Option<T>` with a statically visible **data field**
  on `T`. It evaluates `opt` once. `None` short-circuits without looking up
  the field. If `field: U`, the type is `Option<U>`; if `field: Option<U>`
  the type remains `Option<U>`. In particular `field: Option<Option<U>>`
  remains `Option<Option<U>>`. There is no recursive flattening.
- `opt ?? fallback` requires `opt: Option<T>` and an assignable `fallback: T`.
  It evaluates `fallback` once **only when** `opt` is `None`. It returns `T`.
  The operator associates rightwards, binds less tightly than logical or and
  more tightly than the existing ternary `condition ? true : false`.
- `value?` propagation is **separate**, presently tracked by Java draft #1020;
  this change does not implement postfix propagation and does not change
  `T?` type/formal-omission positions.

`Result<T,E>?.field` is rejected. Neither `None` nor `Err` is an exception,
and `?.`/`??` never turn panics, throws, raises or cancellations into None.

## Current admission boundary

Initial Java runtime supports static fields only, not `?.method()`, optional
indexing, assignment through `?.`, or arbitrary dynamic property lookup.
The ownership pass requires a **provably Copy projected field**, or a
recursively Copy-safe immutable/readonly record field. This permits nested
`Option<record>` chains without introducing mutable aliases. Ordinary
mutable structs, affine classes, actor state and unresolved generics remain
excluded pending partial-move/loan evidence. This narrow projection proof
does not alter the language-wide Copy classifier. Broaden only after
ownership/lifetime/drop safety proofs; unsupported shapes must reject.

## Required validation before merge

Run Java/GraalVM parse/type/ownership and runtime tests on an exact-head
JDK25 runner. Test suspension-aware evaluation, nested Option
typing, lazy fallback side effects, short-circuiting, inaccessible/missing
fields, mixed Option/Result rejection, user-defined type collisions, actor
borrow and affine negative cases. Repeat equivalent fixtures in LLVM and
other maintained backends; never advertise parity based on docs,
unexecuted test files, or zero-step Actions failures.

# Inverted-array bracket indexing v1

Status: **host lowering target implemented; guest execution still fail-closed**

This contract defines how `[]` must behave once executable inverted classes are admitted.
It does not remove the current TypeChecker gate.

## Syntax and lowering boundary

Oreslang bracket syntax is parser-native. `a[i]` is represented by
`Ast.IndexExpr(receiver=a, index=i)`; it is **not** a textual/source macro.

For a statically proven `InvertedArray<T>` where `T` is the matching nominal
inverted class, the Java reference lowering target is:

```
receiver.index(checked_i)
```

`InvertedArray.index(int)` resolves the current dense index through
`InvertedColumnStore.handleAt(int)` and returns a transient row projection that
contains an opaque owner/slot/generation handle. Generated code must never retain
or reconstruct a raw physical row offset as row identity.

Backends may fuse `a[i].field` into a direct checked column operation, but the
observable semantics below must remain identical.

## Evaluation order

Existing Oreslang indexing order is normative:

- read `a[i]`: evaluate receiver exactly once, then index exactly once;
- indexed assignment `a[i] = rhs`: evaluate RHS exactly once, then receiver
  exactly once, then index exactly once.

A future inverted lowering must preserve this order in synchronous and suspendable
paths. Optimizers must not duplicate receiver/index/RHS expressions.

## Index domain

The v1 dense index is a signed 32-bit integer.

- `0` is the first row;
- `size - 1` is the last row when non-empty;
- negative values fail;
- `size` and larger values fail;
- empty arrays reject every index;
- wider dynamic integral values must be range-checked before narrowing;
- floating-point, NaN, infinities, strings, Symbols and other non-integral values
  are not coerced into an inverted row index;
- there is no Python-style negative indexing and no wraparound.

## Logical identity and structural mutation

A successful bracket projection captures the logical row selected at that moment.

- reserve/reallocation does not invalidate it;
- swap-delete may move a different physical row, but surviving logical handles
  continue to resolve to their row;
- deleting the projected row makes the projection stale;
- slot reuse must increment generation and must never revive a stale projection;
- handles from another InvertedArray/store are invalid even when schemas match;
- close revokes all row projections and field loans.

## Fields and records

`a[i].field` resolves only a directly declared inverted-class field.

- direct fields map one-to-one to physical columns;
- nested fixed records remain opaque AoS values inside their one top-level column;
- record values are encoded/decoded defensively and do not expose backing bytes;
- unknown fields fail;
- wrong scalar/record types fail before mutation;
- a failed field write leaves the previous value unchanged.

## Borrowing

Bracket projections do not bypass column loans.

- immutable field loans permit compatible reads but block ordinary writes;
- mutable field loans block ordinary reads/writes of that column;
- structural mutation remains blocked while any conflicting loan is live;
- disjoint column-loan rules remain the authority; bracket syntax creates no
  additional escape hatch.

## Unsupported source forms remain fail-closed

Until separately specified and tested, the compiler must reject rather than
silently reinterpret:

- row replacement through `a[i] = row` for inverted arrays;
- multidimensional `a[i,j]` as an inverted intrinsic;
- slicing/ranges;
- user `Symbol.index` / `Symbol.indexSet` overriding the privileged inverted
  layout operation;
- row projections escaping their checked lifetime, crossing actor domains, being
  hidden in `Future`, or crossing `await` without an ownership proof.

## Backend gate

Enabling executable guest inverted classes requires the same semantics in every
enabled backend. At minimum, qualification must cover bounds, once-only evaluation
and order, reserve, swap-delete, ABA/slot reuse, close, owner crossing, column
loans, fixed-record columns, wrong types, forged/unproven AST entry, and unsupported
forms failing closed.

Zero-step CI is `NOT_EXECUTED`, not passing evidence.

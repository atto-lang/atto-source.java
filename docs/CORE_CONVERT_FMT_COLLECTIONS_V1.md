# Atto core/convert, core/fmt and std/collections — v1

The backend owns these source files. This change does not move code to
atto-common-source and does not claim a compiler-independent implementation.

* `core/convert`: strict ASCII decimal -> signed-i64 Result; bool and u8
  checked conversions; ASCII digit decoding. Signed minimum and maximum are
  accepted, out-of-range inputs rejected. No implicit casts.
* `core/fmt`: canonical signed-i64 decimal, bool text and checked two-digit
  lowercase hex byte. No locale, host reflection, or format-string evaluator.
* `std/collections`: borrowed int-array lookup, sorted lower bound, stable
  uniqueness (4096-item CPU cap), and stable sorted merge (65536-item cap).
  These allocate **owned** result arrays, never mutate borrowed input.
  No HashMap, allocation primitives, generic Clone, or trait dispatch claimed.

## Conformance cases required before promotion

- convert: 0, -0, 9223372036854775807, -9223372036854775808;
  overflow 9223372036854775808 and -9223372036854775809;
  empty, lone minus, plus sign, whitespace, non-ASCII numerals;
  bool only lowercase true/false; byte -1/0/255/256.
- fmt: i64 extremes, zero, +/-1, powers of 10, bool, hex 0/15/16/255;
  negative and 256 rejected.
- collections: empty, duplicates, already sorted, reverse sorted,
  unsorted lower bound rejected; stable unique; merge equal cross-input
  elements; budget 0, 65536, 65537; disallow over-budget before allocation.

## Integration and safety gates

This is a **staged surface**, not a claim of passing compiled-guest
conformance or general-purpose collections. `String.codepoints()`, the
`Array<int>` runtime primitive, `Result`, generic borrow syntax,
`while ... done` and module-path import must be compiled on each backend.
The full mutable collection families in existing stacked Java PR #522
remain separate and must be reconciled before merging overlapping names.

No runtime privilege, FFI, OS, scheduler or arbitrary host collection use.
Actor sendability, borrow loan lifetimes, failure-safe allocation, and AOT
packaging must be tested on exact branch heads before claiming parity.

# Core/alloc/std memory and borrow contract (v2 candidate)

These compiler-owned `.atto` files are a staged implementation, not an
attestation of guest execution, AOT or nine-backend parity.

## Allocation boundary

- `stdlib/core/convert.atto`: purely scalar conversions and negative decimal
  step; `Result<T,int>` uses integer error codes 1..6 and must be represented
  by the compiler without heap allocation.
- `stdlib/core/fmt.atto`: signed-i64 digit count, ASCII digit-by-position,
  boolean ASCII scalar access and hexadecimal nibble encoding. It neither
  formats strings nor touches an allocator. Return code 7 denotes an invalid
  index; code 4 denotes an invalid nibble.
- `stdlib/alloc/text_convert.atto`: `String.codepoints()` allocations,
  strict signed decimal and boolean text parsing with string errors.
- `stdlib/alloc/text_fmt.atto`: string-producing formatting and hex bytes.
- `stdlib/std/collections.atto`: borrowed array queries and new owned array
  results, with bounded CPU cost and element count; it never creates mutable
  aliases to caller storage.

String literals in alloc may be interned; core must not depend on allocation.
The compiler/runtime must demonstrate that scalar `Result` representation is
non-allocating; otherwise core Result APIs are NOT core/noalloc compliant.

## Compiler-enforced borrow obligations (not library heuristics)

1. `borrow Array<int>` is a shared, read-only loan. Mutation or `rt take`
   of this object inside the function is a semantic error.
2. An immutable borrowed input may be used by simultaneous reads but must
   never gain Send/Sync merely through a wrapper or `Result`.
3. A returned `Array<int>` in `Ok(output)` must transfer owned authority
   exactly once. The caller must not observe a borrowed alias or double drop.
4. A borrow may not escape through a `Result`, a closure, a task, a message,
   an iterator or a later suspension; enforce during HIR/MIR lowering.
5. Allocation failure during `unique_int` or `merge_sorted_int` must unwind
   owned temporaries and leave borrowed inputs unchanged.
6. Non-actor code uses the same borrow rules as actor code. Actor state and
   critical-read/write guards retain additional sealed-state restrictions.
7. Compiler rejection for unsupported `.atto` syntax is NOT proof of borrow
   checking. For negative fixtures, require a semantic ownership diagnostic.
8. Signed division must truncate toward zero, or the core ASCII digit
   positional implementation must be backend-adapted before claiming parity.

## Compatibility and dependency audit

Existing Java #522 owns generic `std/collections` implementation on stacked
legacy `.ores` source; do not merge a collision or claim its functionality.
These integer-array algorithms are a narrower non-generic surface.
Cross-backend APIs can match while implementations remain independent.
No unsupported host-language fallback, no source export without the reviewed
private-carrier gate, no `steps=null` evidence counted as executed CI.

## Required execution

- Parse/import/typecheck each of five modules on exact branch head.
- Run core i64 extreme vectors, format ASCII position and negative indices,
  alloc string round-trips, collection budget, duplicates and stability.
- Run positive borrowed read/owned result cases and negative mutation, escape,
  post-take and suspend cases with *semantic*, not parser, diagnostics.
- Verify no core heap use in JVM, GraalVM AOT and LLVM native.
- Verify full compilation, runtime and parity for each other backend before
  elevating its PR from Draft.

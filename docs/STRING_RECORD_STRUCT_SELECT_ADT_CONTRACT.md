# String, record, struct and select/when ADT contract — October 2026

## Status

This document separates the implemented Java/GraalVM **typed select-read
binding** slice from planned text and aggregate data features. It makes no
claim of complete LLVM source/compiler or native AOT parity.

## Strings: scalar text versus an owned editable object

Keep `string` as the builtin immutable, Copy, UTF-8 text **value**
(semantically a Unicode scalar sequence). Preserve legacy `String` as an
alias of the same value until a deliberate source-breaking migration; do
**not** switch `String` to a mutable reference type implicitly, since
existing signatures, overloads, serialization and class/actor contracts
depend on their current equivalence.

Add a separately named owned, non-Copy `StringBuffer` (or
`MutableString` after naming review), and a readonly lifetime-bound
`StringView`. Neither is implemented by this PR.

```ores
const string original = "Alice";
const StringBuffer mut editable = StringBuffer.from(original);
editable[3] = "4";  // proposed result "Ali4e"
editable = StringBuffer.from("Bob");  // rejected: const is not rebindable
// original remains "Alice"

[const int id, const StringBuffer mut name] = get_user_buffer();
name[3] = "4";
```

`StringBuffer` needs an owned or COW representation with no shared mutable
aliases, explicit conversion back to immutable `string`, zero-based
Unicode scalar indexing (not Java UTF-16 code units or UTF-8 bytes),
single-scalar replacement, out-of-range checks, and deterministic
normalization and AOT behavior. Copying an immutable `string` must never
make its source shared-writable. Cross-actor messages transfer serialized
immutable snapshots, not process-local writable cells.

`const string mut name` can be parsed, but cannot perform character
replacement while its value type remains the immutable Copy scalar.
Proposed implementation tracking:
[Java issue #817](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/817).

## Records versus structs: distinct semantic roles

Both are useful, with no redundant reflection-based runtime hierarchy:

| Kind | Role | Identity | Layout/ownership |
| --- | --- | --- | --- |
| `record` (proposed source spelling) | Structural, typed product *contract* / named data shape | Field names and types, not nominal class identity | Describes a closed shape; no implicit mutation or dynamic keys |
| `struct` | Concrete aggregate storage/value constructor | Inline shape today, optionally named nominal type in a future feature | Field ownership, mutability, initialization and drop rules explicit |
| `class` | Nominal identity + methods/inheritance | Nominal | Owned heap object |

Current implementation: record *types* are already expressible as
`{foo: string, count: int}`. `infer struct{foo: "x"}` constructs a
closed readonly aggregate; `struct{foo: string}{foo: "x"}` constructs
a closed explicit struct value, with mutation governed by binding and
field capability. Java's type checker already uses internal `Record`
shapes for these. The source `record` keyword and a distinct runtime
record constructor are **not implemented** here.

Recommended future alias spelling, explicitly reserved/not yet admitted:
```ores
define record EventData as
    foo: string
    count: int
end
```

This would mean a named structural schema, not `class` or hidden nominal
constructor. A concrete `struct` may expose a record view when it proves
the public fields, readonly capabilities and their types. A readonly
`record` view must not grant a writable reference to a mutable struct.
Avoid a second untyped map system called record: dynamic-key maps already
have their own use case and cannot safely be passed as a compile-time
closed schema without validation.

No `infer record` in select patterns. `infer` creates a value/shape
from an initializer, but readch already knows its payload type from
`Channel<T>`. Match or destructure that value using the channel's
compile-time type. Errors are checked before registration, not after a
channel message is consumed.

## Canonical readch select/when

The channel selection chooses **one ready arm atomically**, then binds
and statically projects **one received message** into an arm-local scope.
It must never consume a second message, reinterpret failed projections
as a non-ready case, or leak a partial record binding.

```ores
fnc consume(
    Channel<int> foo,
    Channel<{foo: string, count: int}> bar,
    Channel<Tuple[int, string, bool]> tupleChannel
): void {
    select {
        when readch foo: int f -> {
            stdio.println(f);
        }
        when readch bar: const {foo: string} -> {
            stdio.println(foo);
        }
        when readch tupleChannel: const [_, string label] -> {
            stdio.println(label);
        }
    }
}
```

Canonical pattern forms:

- `when readch ch: int x -> { ... }`: inferred `const` kind,
  explicit payload type that must be a valid widening of `Channel<T>`.
- `when readch ch: val x -> { ... }`: old binding form remains accepted.
- `when readch ch: const [int id] -> { ... }`: tuple **prefix** projection,
  exact member-index checking and no binding for unselected suffix.
- `when readch ch: const {foo: string} -> { ... }`: closed record/struct
  field projection; only requested fields bind, no runtime-key discovery.
- `when readch ch: const item: {foo: string} -> { ... }`: keep the
  **whole record** as a typed local instead of binding its fields.
- `when writech ch, value: -> { ... }`: write arm has no read binding.

A sum payload (Option/Result/tagged enum) still requires its
constructor to be proven in an inner `match`; a ready `readch`
cannot be skipped based on payload shape without an explicit filtered
receive protocol.

`when` is the canonical arm keyword. Legacy `case` remains accepted
for this stage; formatter may normalize it. `-> { ... }` is the canonical
body delimiter, while older `{ ... }` remains accepted. `nb select`
retains the same per-arm type/ownership restrictions and delayed callback
lifetime, so a projected borrow cannot escape into deferred execution.

## Proof requirements

1. Parse, type-check and own every binding in the selected arm only,
   including nested mutable/readonly capability restrictions.
2. Reject wrong expected type, absent object field, too-long tuple prefix,
   duplicate arm-local names, non-final rest and borrowed references whose
   projection lifetime is not tracked.
3. Runtime writes and reads remain atomic with respect to channel selection;
   projection failure raises a *real* error and cannot silently select
   another ready arm.
4. Record field projection stages checks before any lexical binding
   becomes visible.
5. Verify with Java/GraalVM JVM + AOT/hybrid exact-source tests, then LLVM
   native frontend tests and MessagePack shape parity. Do not count codec
   tests as source-level compiler support.

See [Java PR #815](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/815),
[LLVM issue #317](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/issues/317),
and [string issue #817](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/817).

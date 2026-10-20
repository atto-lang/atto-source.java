# Tuple runtime kind (Java/Graal integration candidate)

**State:** compiler/runtime candidate, NOT production-certified. This branch is
stacked on the current-main tuple/Entry integration, followed by the zero-arity
parser/type/ownership hardening. The standalone JDK21 carrier smoke is **not**
an exact-head JDK25 Maven/Graal or LLVM validation.

## Source kinds

- `tuple {1, 2}` and `(1, 2)`: positional `Tuple<int,int>`.
- `tuple {}` and `()`: empty positional tuples.
- `tuple {1}` and `(1,)`: singleton tuples.
- `[1, 2]` and `arr[1, 2]`: Array values, never tuples.
- `Entry.of(k, v)`: nominal key/value Entry, never a two-slot tuple.
- `tuple(1, 2)`: ordinary callable (legacy compatibility resolution applies).

## Carrier and guest semantics

`OresTuple` is a final immutable tagged carrier with indexed read/iteration
compatibility through `AbstractList`. Its host `equals/hashCode` are
identity-only and its host `toString` is opaque; these cannot traverse hostile
user-supplied payloads or redefine guest-language equality.

The evaluator constructs `OresTuple` in sync and suspendable tuple literals,
preserves the tag through async detachment, `Entry` nesting, and actor freeze
and private-isolation copies. Tuple patterns and reified tuple type predicates
require the actual nominal `OresTuple` tag. Array/List runtime predicates
explicitly exclude it. Guest `==` compares two tuples positionally using
guest value equality and never considers an Array with matching elements equal
to a tuple. Indexed and collection-method tuple mutation is rejected before
generic List runtime fallback.

Guest diagnostic display renders `tuple {...}` separately from `[...]`.

**Contextual return compatibility:** the older language contract permits a
literal Array-shaped `return [e1, ...]` in a callable explicitly declaring a
finite tuple result. Runtime return shaping still admits a sequence and
creates a new `OresTuple`. This must remain restricted to a compiler-proven
contextual conversion; it must not become an unchecked host-entry adapter.
Follow-up work should carry a checked return-expression proof through this
boundary, and reject unproven dynamic Array-to-Tuple coercions.

## Required remaining security and parity gates

1. Execute exact-head Maven/JUnit/JDK25 and real Graal guest tests; inspect
   job-level steps, logs and artifacts rather than treating runner-allocation
   failures as a compiler regression or success.
2. Fuzz nested `Option`, `Result`, `Entry`, typed returns and moves/borrows,
   including host-entry and suspension adversarial cases.
3. Audit every serializer/deserializer, FFI, dynamic host-input adapter and
   hot-reload state migration for `OresTuple` vs Array/List tag preservation.
   Do not serialize only the positional elements without their kind.
4. Native LLVM must reject unsupported tuple syntax before emitting IR until
   an independently checked native tuple ABI and differential fixtures exist.
5. Do not cross-org mirror this private source without the reviewed private
   destination, protected origin branch, exact carrier contract and pinned
   source-tree proof required by the checked-in security policy.

# Core assertions

Import the source-owned module with `import module Assert from "std/assert";`.

| Predicate | Meaning | Failure-producing counterpart |
| --- | --- | --- |
| `equal(Any a, Any b)` | Language `eq` equality | `equal_throws(a, b)` |
| `not_equal(Any a, Any b)` | Language `neq` inequality | `not_equal_throws(a, b)` |
| `same(Any a, Any b)` | Language `is` identity | `same_throws(a, b)` |
| `true(bool actual)` | Value is true | `true_throws(actual)` |
| `false(bool actual)` | Value is false | `false_throws(actual)` |

Predicates return bool without failing. Counterparts return true on success and
produce an ordinary runtime failure on mismatch. `Assert.fail(String message)`
unconditionally produces an ordinary failure with the supplied message.
Equality follows the language operators; this module does not introduce recursive
collection comparisons or approximate numeric comparisons.

```ores
import module Assert from "std/assert";

trap fnc check_value(int actual): bool {
  return Assert.equal_throws(actual, 42);
}

pub routine main(): void {
  val Option<bool> good = check_value(42); // Some(true)
  val Option<bool> bad = check_value(7);   // None
  stdio.println(good.is_some());
  stdio.println(bad.is_none());
  return;
}
```

Both `Assert.equal_throws(a, b)` and `Assert::equal_throws(a, b)`
resolve the same source-owned module callable. The `::` separator is an
explicit qualified-member spelling, not a host static call, reflection or a
separate assertion implementation.

The `trap` keyword may modify a function or prefix a call-site expression:
`const result = trap Assert::equal_throws(10, 20);`.
An expression-level trap yields `Some(value)` on success or `None` for an
ordinary guest failure; the function modifier similarly wraps its return.
No diagnostic error detail is retained by trap. Untrapped assertion failures retain the message and can be caught by
ordinary try/catch. Panic, cancellation, process termination, and capability or
permission denials must bypass trap: they are not assertion mismatches.

The source module uses `rt fail(String message): void`, a general runtime failure
operation. The host only transports that failure; predicates and messages live in
Oreslang. This operation deliberately does not enable the reserved `throw`,
`raise`, `panic`, `recover`, or proposed `throws` syntax or typed error effects.
`true` and `false` may name declared callables and qualified members; bare boolean
literals retain their existing meaning. Call these functions through `Assert`.

The LLVM repository carries the same `stdlib/assert.ores` source, but its current
compiler cannot execute it: stdlib imports, Any/bool APIs and ordinary failure/trap
lowering still require implementation. This is not native execution parity.

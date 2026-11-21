# Multidimensional indexing contract

Oreslang treats one bracket expression as one indexing operation. Comma-separated indices remain in one AST node, so `value[i, j]` is not equivalent to `value[i][j]`.

Custom classes may opt into indexing with sealed symbol methods:

```ores
pub [Symbol.index](int row): Row { ... }
pub [Symbol.index](int row, int col): T { ... }
pub [Symbol.indexSet](int row, int col, T value): void { ... }
```

The language-facing indexing arity counts index operands only. The assigned value is a setter operand, not an index. Getter and setter are separate protocol families.

Built-in Array/List/Tuple/closed-struct indexing remains unary. A built-in value with two or more comma-separated indices is rejected rather than recursively interpreted.

Evaluation order is deterministic. For a read, evaluate receiver first and then every index operand from left to right, exactly once. For an indexed write, evaluate receiver first, then every index operand from left to right, then the assigned value, then invoke the setter operation. The synchronous and suspendable evaluators must use the same ordering.

Index protocol methods are instance methods. They must be synchronous and non-suspending. Index protocol parameters are ordinary by-value parameters in the current safety slice. Projected borrows through custom indexing and mutation through a custom getter projection remain rejected until alias/provenance semantics are proven.

An indexed write requires mutable authority to the receiver. A read-only binding cannot gain mutation rights merely by declaring Symbol.indexSet.

Protocol selection is arity-first. Parameter types and return types do not create another slot; they are checked only after the arity slot has been selected. Symbol.indexSet must return void and must accept at least one index plus the assigned value. Symbol.index must accept at least one index and return a value.

# Copying primitive data out of a loan

`rt copy value` applied to a borrowed primitive produces an independent immutable
primitive value. For example, a `borrow int n` parameter may return
`(rt copy n) + 2`. The original borrow and its lifetime remain active; this does
not move its owner or grant mutation permission.

Both the type checker and ownership checker must agree that the result is Copy.
Nominal objects, containers, channels, guards, closures and other capabilities
retain their existing copy/borrow rules. `rt take` and `rt share` do not turn a
borrow into owned data. No pointer punctuation or implicit loan escape is added.

`PrimitiveBorrowCopyTest` executes integer and boolean copies in real guests and
checks that container loans and take/share remain restricted. Cross-backend
module fixtures exercise the same explicit integer-copy source in LLVM and Java.

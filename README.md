# Oreslang

Oreslang is a statically typed, structurally typed language for GraalVM/Truffle with actor-oriented concurrency and a stricter isolate mode intended for FaaS/security sandboxes.

This repository contains the Java/Truffle reference implementation.

The language is intentionally opinionated:

- static structural typing;
- private functions by default (`fnc`), with `pub` for exported functions;
- class methods omit `fnc` and have an implicit `self` receiver;
- one return value only (tuples/arrays/records are ordinary single values);
- `val`, `const`, and `let` are the only variable declarations;
- actor heaps are isolated: mutable values are never shared between actors;
- immutable/sendable values may be message-passed, and explicitly frozen regions may be shared read-only;
- isolates are stricter security boundaries for FaaS workloads, with capability-denied host access by default;
- multiple named modules may appear in one source file;
- explicit `return` statements;
- generics, tuples, arrays, complex numbers, futures/`await`, lambdas, `defer`, and `try/catch/finally` are language-level features.

The first implementation is developed on a feature branch and will land with an executable Truffle skeleton, grammar/specification, examples, tests, and CI.

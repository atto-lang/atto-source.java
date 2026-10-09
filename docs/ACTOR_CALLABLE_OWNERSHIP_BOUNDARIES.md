# Actor and namespace callable boundaries

Actor classes, instances, ActorRef handles, module descriptors and class descriptors are non-first-class callable data. Ordinary functions, methods, lambdas and constructors cannot receive them, including nested in known generic types or containers. Call static members directly instead of passing a namespace.

ActorRef.send(message) remains a dedicated mailbox primitive, not an ordinary function that receives the actor itself. Each actor continues to own its guest state and has one admitted guest turn.

rt borrow borrows without transferring ownership; mutable borrowing is exclusive. rt take consumes ownership and invalidates the source binding. rt copy and rt share remain restricted to values statically proven Copy: move-only objects fail closed pending full deep-copy and immutable Shared<T> lowering. No mutable graph alias is manufactured.

The ownership pass checks known signatures, calls and imported aliases. The interpreter repeats actual guest-call argument checks as defense in depth for dynamic values. Actor references may still be used for mailbox routing, not ordinary userland function parameters.

This is a draft contract, not proof of every host-interop escape or imported-module state partition. JVM integration tests and Rust-to-Java refinement remain necessary before merge.

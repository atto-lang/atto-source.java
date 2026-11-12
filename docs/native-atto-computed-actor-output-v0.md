# Checked computed actor output — experimental native Atto result v0

This is a narrow compiler-backed extension of Java #2981, not a generic
`SupervisorActor` or `ServiceActor` implementation.

Native `.atto` actor receive now permits precisely one source-owned
`p.send(mail.value)` or `p.send(mail.value + constant-i64)` statement **after**
the fully checked critical state operations. The compiler verifies the actor
receiver, owned current `ActorMail<int>` parameter, exact plus operator,
literal addend, private trusted ingress, and immutable source code image.
This does not permit arbitrary guest callbacks, direct state alias reads,
dynamic expressions, multiple outputs or reordering around critical loans.

The backend executes `Math.addExact` on the actor's actual serialized
mailbox turn. Arithmetic overflow fails the actor turn before publishing any
output. The actual bounded ActorOutput channel can return the derived scalar
through the experimental closed-world `main()` projection. A healthy sibling
actor continues processing even when the first fails. No supervisor CONTROL
capability is created by either source declaration or return expression.

The fixture `contracts/namespace-actor-state-v1/checked-computed-output-readback.atto`
sends 41 to a native actor, computes +1 within its receive turn, and returns 42
to the real Atto `main()`. General async `await`/Future/Option/Result
semantics, compiler-owned supervised failure notices and typed service replies
remain separate, unimplemented gates.

Run the new `NamespaceActorComputedOutputGuestTest` and
`NamespaceActorComputedOutputRuntimeTest` alongside all previous namespace
actor/JVM tests with exact Git blob identities. Do not claim full source tree,
Graal Native Image, LLVM, Wasm, Forge, or .NET certification until independently
executed compiler/guest receipts exist. Keep draft pending security review.

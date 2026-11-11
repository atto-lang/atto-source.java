# AttoLang actor receive ingress boundary v1

## Normative rule

An actor's receive(ActorMail<T>): void is a runtime-only mailbox handler, not a
public or protected instance method. The actor scheduler dispatches it after
mailbox admission, bounded queueing, ownership transfer and execution-lease
acquisition. Guest source MUST NOT directly invoke or capture an actor's
receive handler, regardless of receiver expression, alias, inheritance,
compilation unit, or source visibility. Resolution MUST distinguish a
lexically shadowing callable named receive from the actor handler itself.

- self.receive(mail) and unqualified receive(mail) inside an actor: reject.
- actorValue.receive(mail), including typed aliases and cross-file references:
  reject once the call resolves to an actor mailbox handler.
- ref.receive(mail): reject. ActorRef<T> does not expose receive.
- ref.send(payload): valid when the payload is owned, sendable and matches
  the declared ActorMail<T> payload; it is lowered to runtime admission.
- Captured or reflected method references to actor receive: reject.
- An ordinary non-actor class may use receive as an ordinary method name.
- Host/runtime dispatch to actor receive is privileged and does not create a
  guest-callable entrypoint or bypass runtime identity/generation fencing.

A source declaration without a body (or a public receive declaration) is not a
valid concrete actor handler under the current Java reference compiler contract.

## AOT and backend requirements

Enforce this rule during semantic call resolution against the final method
symbol, not only by comparing the spelled identifier self. Do not generate a
direct-call ABI or an exported callable pointer for actor receive. Retain one
mailbox ingress and one execution lease per actor. ActorRef.send must remain
subject to sendability, bounded admission, cancellation and supervisor
generation checks; no alternate direct actor dispatch may be generated.

Canonical positive and negative .atto fixtures live under
src/test/resources/atto/actor-ingress/. Java reference checks are in
ActorReceiveIngressBoundaryTest. LLVM, .NET NativeAOT, Wasm, Forge/Cranelift
and JS must run equivalent compile-fail tests and one actual mailbox delivery
guest test on pinned source heads. A source-only checker test or zero-step CI
does not certify guest execution or cross-backend parity.

## Threat-model regression additions

- An actor-local lambda bound to the name receive may still be invoked as a
  normal local callable; it is not a privileged mailbox entrypoint.
- A self alias cannot be used to call the actual receive handler.
- An inherited actor receive remains runtime-only.
- A cross-file imported actor's receive cannot be called directly through a
  typed actor instance. Semantic resolution must preserve actor metadata.
- These fixtures are compile-time tests, not scheduler delivery receipts.

## Guest-execution acceptance

In addition to compile-fail fixtures, run
`ActorReceiveIngressGuestExecutionTest` against an exact compiler commit.
Its `.atto` program uses `spawn`, `ActorRef.send`, runtime receive,
actor output and `get_done`, with a bounded JUnit timeout. It must print
exactly `delivered`. Passing type checking without evaluating the guest is
not sufficient evidence. If the GitHub runner starts with zero steps, report
runner-admission failure rather than guest test success or failure.

## Imported actor nominal metadata (linker regression)

A linked `import actor Worker from "./worker"` is a nominal ACTOR import,
not an opaque untyped value import. The import graph proves exported actor
kind and public visibility, and the incremental compiler MUST pass that
checked actor signature into TypeChecker and OwnershipChecker. Direct
`worker.receive(mail)` is always rejected at resolved actor-method ingress;
`ActorRef<Worker>.send(value)` uses `Worker.receive(ActorMail<T>)` to check
the payload type. A missing linked actor signature is an admission error,
never permission for unknown-method dispatch.

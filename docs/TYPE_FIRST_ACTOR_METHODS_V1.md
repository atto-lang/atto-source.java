# AttoLang type-first external actor methods — v1 candidate

**Status:** Draft, not merged. No Java guest or native backend execution
receipt exists for the candidate head. This document defines the implemented
Java front-end admission boundary; it does not claim backend parity.

## Canonical source

```atto
use namespace workers;

declare actor Foo1;
declare actor Foo2;

mtd (Foo1 self) foo(): int { return 41; }
mtd (Foo2 self) foo(): int { return 42; }

mtd (Foo1 mut self) receive(ActorMail<String> mail): void {
  self.send(self.foo());
  self.end();
}
mtd (Foo2 mut self) receive(ActorMail<String> mail): void {
  self.send(self.foo());
  self.end();
}
```

For a valid sendable message, the existing guest fixture spawns both actors,
sends one message each, consumes both output streams and expects `41|42`.
A source fixture alone is **NOT** a passing execution.

## Exact declaration and dispatch rules

- `declare actor X;` and `declare isoactor X;` declare nominal actors,
  not object instances or actor mailboxes.
- A same-scope `define actor X as ... end` may complete a forward, provided
  kind, visibility and generic signature match exactly. Such an actor's sealed
  body cannot be extended by external receiver methods in v1.
- Otherwise, **one or more** local external methods on the forwarded actor
  complete an **empty-state** executable `Ast.ClassDecl` with a serial
  `GENERIC` actor concurrency policy. The forward by itself does not
  create an actor. This is source-local: no cross-file implicit completion.
- The actor's nominal class identity includes its defining module; the file
  `use namespace` header is retained as part of source identity. Identical
  actor names in different modules are distinct, including inside ownership
  checks.
- Method identity is receiver owner + name + arity, not receiver mutability
  or parameter/return type. Same method name on Foo1 and Foo2 is legal;
  duplicate Foo1.foo/0 is not.
- Actor ClassDecl slots are created in **forward declaration order**, not
  first-seen external mtd order. Method bodies may precede their forwards.
- `mtd (Foo self)` uses a read-only actor self loan.
  `mtd (Foo mut self)` uses an exclusive mutable self loan.
  Actor mailbox handlers `receive(ActorMail<T>)` require `mut self`.
- Neither receiver kind may escape its actor's mailbox turn, be returned,
  captured into an independently scheduled task, or cross an ordinary
  callable boundary without an explicitly checked actor-specific contract.
  `self.send`, `self.end` and `self.endWithCleanup` require the exclusive
  actor self loan, including inside deferred `nb select` continuations.
- Defined actors keep their existing sealed-state, inheritance, ownership,
  actor-isolation and transport admission rules. External actor methods may
  not shadow compiler-owned state/lifecycle operations.

## Hard failure / deliberately unsupported

- Unknown receiver, cross-module receiver capture or unresolved forward
- Mismatched actor/isoactor/class kind or forward visibility
- Duplicate declaration, duplicate same-name/arity receiver method,
  actor declaration with unsupported generic receiver methods
- Extending an actor that has a `define actor` body
- Spoofing built-in actor control methods and state access
- External `on_start`, `tear_down` and other privileged lifecycle hooks:
  remain unavailable until explicit base/trait hook admission and effects
  are settled
- Sending to a declaration-only actor with no valid
  `receive(ActorMail<T>)` handler

## Execution profiles and portability

Java/GraalVM is a managed-host implementation of the guest compiler/runtime;
this syntax does not by itself prove a zero-process-GC memory profile or
sealed guest AOT support. Actor instances use the existing source runtime's
owned mailbox and actor-state engine. A new syntactic declaration creates no
new host OS, network, reflection, or JIT capability.

The LLVM native front-end currently recognizes the type-first spelling for
classes; declaration-only actor `mtd` lowering fails explicitly with
`NATIVE_ACTOR_MTD_BACKEND_UNSUPPORTED`. The .NET NativeAOT frontend
recognizes the syntax but refuses to emit CIL with
`MTD_RECEIVER_NATIVEAOT_UNSUPPORTED`. WASM and Forge parity is pending.
Do not infer runtime parity from syntax recognition.

## Verification requirement before merge

On the **exact PR head**, execute:

```sh
mvn -B -ntp -Dtest=ActorForwardNamespaceReceiverTest,ExplicitReceiverMtdV1Test test
```

Collect a nonzero-step GitHub Actions job (or security-approved ephemeral
source-owned runner), commit SHA, root tree SHA, fixture blob SHA, GraalVM
version, JUnit counts and the guest's exact `41|42` stdout. A failure before
runner assignment, `steps=null` or `steps=[]` means **NOT_EXECUTED**.

Private-source export to a separate org requires the reviewed authorization
and source branch protection in
`docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`. A funded private alternate
org, by itself, is not authorization to transfer source.

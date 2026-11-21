# Namespace-attached sealed actor state: static admission v1

**Status:** candidate source/AST/binder implementation only. This branch does **not**
execute external `mtd`, actor state, `init`, or `handle_state`. Do not report
a passing guest compiler, runtime, Native Image, or any other backend.

## Goal and canonical syntax

A source actor's nominal type, external receiver methods and private state
schema may live in different files of **one verified package and namespace**.

```atto
// foo/actor.atto
namespace foo;
declare pub actor Foo implements ActorXYZ;

// foo/state.atto
namespace foo;
define state on actor Foo as {
  let int x : 5;
  val bool ready : true;
}

// foo/methods.atto
namespace foo;
pub mtd (Foo f) bar(): void {
  return;
}
```

`declare actor Foo implements ActorXYZ;` is a distinct **declaration-only nominal type**, never an empty `ClassDecl`, actor instance, ActorRef, singleton or static value. A full body uses `define actor Foo as ... end`; `define actor Foo;` is rejected to avoid misrepresenting an incomplete definition as implemented. This preserves the difference between declaration and implementation. `pub mtd` without `define`
is a parsed alias of `define pub mtd`. Neither syntax authorizes a free
function, actor-owned self value, state field exposure or caller-dispatched
lifecycle invocation. `ActorXYZ` contract satisfaction by external methods
remains a future linker/type-checker obligation, not a proof provided by this
draft. A forward-declared actor with `implements` must never be accepted as
contract-conformant merely because its methods appear in a textually equal
namespace.

## Single owned state root

`define state on actor Foo` is NOT `define state container`: the former is
attached to each activated `Foo` instance, with one unique typed layout,
while the latter has a different protected-component contract. State does not
become a namespace variable or an independent heap owner.

The v1 parser/AST admission subset is intentionally limited to:
- One state schema for a locally declared nominal actor in an explicitly
  provided source set, at namespace root (never named module scope).
- 1..32 uniquely named private fields per state schema.
- `let int/bool` and `val int/bool` with exact type-matched literal
  defaults. No effectful defaults, containers, async, reference-bearing
  initializers, inferred values, or unvalidated ADT layouts.
- No coexistence with legacy actor instance fields; generic/inherited
  schemas require explicit layout witnesses and currently fail closed.
- Path provenance, package/namespace identity and cross-file duplicate slot
  checks inherit the existing `ActorReceiverBinder.bindSourceSet` static
  boundary, which is **NOT** itself a manifest-verified package loader.
- No public state declaration, state export, external pointer/borrow or
  actor-context authority. An attached schema never satisfies a contract
  field requirement.

A future executable lowering must establish a manifest-pinned source set
through `PackageNamespaceBuild` or equivalent checked package provenance,
then construct a *single* immutable actor type/contract/method/state witness
before type checking and native code generation. Reject any changed manifest,
missing source unit or state layout version mismatch.

## Only compiler-minted critical access

The intended source-level actor body uses the existing sealed-state intrinsic:

```atto
pub mtd (Foo f) increment(): void {
  f.handle_state(critical-write |s| -> {
    s.x = s.x + 1;
  });
}
```

This is **future executable syntax**, not a claim about this branch.
The receiver `f` in a bound method must denote the already authenticated
*current actor execution*, not a user-created `Foo` value. A caller may hold
only an authorized `ActorRef<Foo>`. There are no free-form `get_state()`
or `set_state()` APIs and no `f.x` field access.

- `critical-read`: actor-owned shared immutable borrow, no writes, mutable
  alias escape, mutation through containers or callback retention.
- `critical-write`: unique actor-owned mutable borrow, exclusive against
  all reads/writes; *including check-then-update atomically* in one loan.
- Both: lexical, synchronous and non-suspending; reject `await`, nested
  critical loans, stored/returned loan views, deferred actions, captured
  mutable state pointers, cancellation leaks, stale incarnation/generation,
  untrusted tenant crossings and access outside an authenticated actor turn.
- Actual reader concurrency, fairness and frame ownership need the physical
  `ActorStateRwLoans` integration; serialized actor turns are not evidence
  of concurrent-reader admission. Compiler static typing alone is not a
  substitute for runtime actor-ID/generation validation and revocation.

No runtime is enabled by parsing the schema. Current `TypeChecker`
intentionally issues `ACTOR_STATE_ATTACHMENT_LOWERING_UNSUPPORTED` after
static same-unit binding. Cross-file linker, ownership checker, HIR/MIR,
actor arena layout and runtime still need an actual integrated implementation.

## Init is one-shot, not a public method

```atto
pub mtd (Foo f) init(): Foo { return f; } // reject
```

The prior namespace receiver security draft forbids external `init`
outright. Before enabling a safe lifecycle form, use the separate reserved
one-shot init proposal: typed spawn arguments; state defaults/complete
installation once before readiness; no raw actor return; rollback on error;
generation fencing, guaranteed cleanup and supervisor notification. `init`
must not be callable from `ActorRef`, ordinary same-turn dispatch, or a
different actor. A future syntax should return a sealed state value (or void
for a complete default schema), never `Foo` or a borrowed state view.

## Test proof and merge gates

`NamespaceActorStateAttachmentTest` covers parser/AST retention, compact
actor contract head, `pub mtd` alias, static three-file binding, wrong
namespace, class impostor, duplicate schemas, legacy field collision,
inherited layout, public state, duplicate fields, type/default mismatch,
effectful defaults, structural types, wrong module and fail-closed execution.
Physical fixture blobs live in `contracts/namespace-actor-state-v1/`.

The focused source-org exact-head Java JDK25 workflow requires this test
suite, existing receiver suites, nonempty/unskipped Surefire reports, source
tree identity and fixture SHA256s. **Authored does not mean executed.** Source
Actions historically fail runner admission with zero steps; a source-free
alternate runner is not permission to mirror private source.

Dependency tracks: actor contracts #2792, manifest-bound namespace #2813,
cross-file receiver #2823, receiver safety #2886, sealed critical Java #2796
and #2256 one-shot actor init, LLVM sealed state #1654, .NET admission #265,
Wasm/Forge executable parity. Treat sibling PRs as independent until a
reviewed integration commit includes and tests them all.

Release requires real Java/Graal guest execution, same-turn and remote
ActorRef calls, negative compiler fixtures, atomic state initialization and
abort, stale-epoch/cancellation/supervisor/tenant races, native AOT and
backend-specific proofs. Unsupported backends must fail closed.

## Canonical declare/define distinction (2026-11-11)

A bodyless actor with behavior or state implemented in other files MUST use
`declare actor Foo implements ActorXYZ;`. A complete inline actor retains
`define actor Foo as ... end`. The parser preserves the former as a separate
`DeclaredActorDecl` instead of manufacturing an empty executable
`ClassDecl`. Neither form automatically grants mailbox authority or a
state loan.

Visibility is private by default. The example uses
`declare pub actor Foo implements ActorXYZ;` because it exports a `pub mtd`
receiver. `declare actor Foo;` is syntactically valid but a public receiver
method on a private actor is a static error. This prevents private actor
type exposure through method exports.

Contract satisfaction, codegen and ABI export of `declare actor` still
fail closed at the linker/TypeChecker boundary, even if the static binder
finds matching methods and state. No executable actor is implied.

The comprehensive `NamespaceActorStateAttachmentTest` class now has
**28 authored JUnit tests**, NOT EXECUTED at this time, including distinct
AST/visibility, bodyless define rejection, duplicate and mixed declaration
collisions, illegal inline bodies, unsupported generics, duplicate contract
claims, and unlowered guest execution.

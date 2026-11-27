# Manifest-bound namespace actors — integrated static topology, stage 1

> **DRAFT / NOT EXECUTED.** The new code is a metadata inspector, not an
> executable guest actor. It does not compile `declare actor` into an actor
> heap, actor-state critical loan or runtime mailbox.

## Reuse, not a parallel package dialect

This integration carries forward the package-root and file-inventory security
work from Java draft PR #2813 into the parser/state/receiver branch #2892.
It uses the existing `.atto.pkg.toml`, `OresProjectConfig`, and
`[project].name/version/root` plus `[source].roots` settings:

```toml
schema_version = "1"

[project]
name = "counter-demo"
version = "0.1.0"
root = "."

[source]
roots = ["src"]
```

Example package:

```text
counter-demo/
  .atto.pkg.toml
  src/foo/actor.atto
  src/foo/state.atto
  src/foo/methods.atto
```

`actor.atto`:

```atto
namespace foo;
declare pub actor Counter;
```

`state.atto`:

```atto
namespace foo;
define state on actor Counter as {
    let int x: 5;
}
```

`methods.atto`:

```atto
namespace foo;
external mtd (Counter c) init(): void { return; }
external mtd (Counter c) receive(ActorMail<int> m): void { return; }
pub mtd (Counter c) increment(): void { return; }
pub mtd (Counter c) get(): int { return 5; }
```

This example is **not** an executable counter: the ordinary methods contain
placeholder bodies and no critical state lowering exists.

## API and boundaries

```java
var config = OresProjectConfig.load(Path.of(".atto.pkg.toml"), Map.of());
var topology = new PackageNamespaceBuild(config).inspectActorTopology();
var actor = new ActorMethodIndex.ActorKey(
        topology.packageIdentity(), "foo", "Counter");

var declaration = topology.actorMethods().actor(actor);
var state = topology.attachedStates().get(
        new ActorStateBinder.Slot(topology.packageIdentity(), "foo", "Counter"));
var get = topology.actorMethods().method(
        new ActorMethodIndex.MethodKey(actor, "get", 0));
```

The source inventory is a single bounded manifest-selected `.atto`/`.ores`
set. The inspector reads and parses all its files before building any actor
slots; no caller-supplied `Map<String, Program>` becomes a manifest proof.
Import provenance uses the existing project graph, not environment path
search. Each actor is keyed by package name/version, source namespace and
nominal name. State schema and out-of-line methods retain separate declaring
source IDs. A declared actor and an inline actor cannot be made into one
state layout. Lifecycle ingress `external mtd init/receive` is validated but
**never** indexed as a public method.

Each source unit additionally carries a SHA-256 digest of its exact decoded
UTF-8 bytes. A source edit invalidates that receipt on the next scan; the
manifest digest is checked before and after inventory reads. These receipts
support reproducible review, not protection against a concurrent filesystem
race. Out-of-line methods remain **ineligible as actor-contract witnesses**
until their actual bodies pass type/effect checking.

The result maps are immutable metadata, and deliberately contain no callable
method body, heap pointer, actor ref or runtime permit. An ordinary invocation
still encounters `STANDALONE_ACTOR_LOWERING_UNSUPPORTED` or
`ATTACHED_ACTOR_STATE_CRITICAL_LOWERING_UNSUPPORTED` from TypeChecker.
Declaring `implements ActorXYZ` remains **fail-closed**, pending exact
actor-targeted signature/type/effect verification from #2792.

## Inventory rejection tests

The new `ManifestNamespaceActorTopologyTest` authors 27 JUnit checks for:
manifest-bound source inventory and separate source provenance, unexposed
init/receive, TypeChecker's no-guest-execution fence, actor deletion and
out-of-root replacement, duplicate/wrong-namespace state and receiver
attachments, duplicate method slots, private actor/public receiver mismatch,
forged lifecycle spelling, unsupported contract witness, noncanonical
visibility, manifest replacement, symlinked source, cross-namespace actor
isolation, untrusted imports and immutability.

These tests **must actually execute** at the exact SHA in
`manifest-actor-topology-junit.yml`, alongside six older actor test suites,
with nonempty GitHub job steps and non-skipped Surefire XML. A clean Git
head/tree receipt is not sufficient to claim guest execution.

## Immutable actor descriptors — added integration gate

`PackageNamespaceBuild.inspectActorTopology()` now returns an
`ActorDescriptorIndex` through `topology.actorDescriptors()`. For each
`(package@version, namespace, actor)` it records:

- the one manifest-owned nominal declaration and its source unit;
- private sealed `int` state field names and checked constant i64 initializers;
- ordinary out-of-line method signatures with `unloweredExternal=true`;
- private runtime-only `init/0` and `receive/1` entry points, including
  their distinct source-unit provenance; these never enter the public method
  lookup index;
- the deterministic SHA-256 of the complete, sorted manifest source receipt
  set, length-delimited and scoped by package identity.

`Descriptor.guestExecutable()` intentionally returns `false`. These
records are immutable **compiler inspection artifacts**, not unforgeable
physical actor permits, contract witnesses, method implementations or
runtime dispatch slots. The constructor representation must not be used
to bypass `TypeChecker` or manufacture a `RuntimeActorRef`.

```java
var descriptor = topology.actorDescriptors().descriptor(actor);
assert !descriptor.guestExecutable();
assert descriptor.stateFields().get("x").initialI64() == 5L;
assert descriptor.runtimeIngress().containsKey("init");
assert descriptor.runtimeIngress().containsKey("receive");
```

The inspector rechecks each descriptor member's nominal identity, actor,
receiver, state and manifest source provenance, rejects forged/missing
source receipts, and independently validates `receive(ActorMail<T>)`.
Changing source bytes invalidates its receipt; a foreign package with
the same namespace or actor name cannot inherit a descriptor.

### Contract and runtime restrictions

The actor-targeted contract parser/type checker in draft #2792 is based
on a **different compiler branch**. It is not safe to treat an ordinary
module `contract` or a name/arity match in a method index as an actor
contract. This branch still rejects `declare pub actor Foo implements
ActorXYZ;` with `ACTOR_CONTRACT_WITNESS_UNSUPPORTED`. A future integration
must prove exact public signature and checked body/effects in a single
manifest-resolved actor-only contract before this rejection can be removed.

Physical critical-read/write locks and actor-owned state frames from draft
#2796 are also not merged into this descriptor branch. No mutable state
view, guest scheduler, activation, mailbox send or physical generation
fencing is made available by this feature. Runtime tests are distinct
from JUnit metadata tests.

## Next compiler/runtime gates

1. Reconcile this new inspector with #2813 (manifest namespace) and #2792
   (actor contracts) on one branch, without duplicate registries. Reject
   missing/wrong-signature contract methods and inherited/generic ambiguity.
2. Reconcile #2796 physical actor RW loans, execution frames and cancellation
   fences; adapt state allocation to `Ast.ActorStateDecl`.
3. Lower receiver methods into a single actor-owned dispatch table. Only
   runtime-controlled activation may invoke `external init()`; only
   validated mailbox admission may invoke `external receive(ActorMail<T>)`.
4. Implement `handle_state(critical-read ...)` and
   `handle_state(critical-write ...)` with no suspend/escape, FIFO writer
   fairness and actor-owned generation identity, not an ambient lock.
5. Prove two distinct Counter activations (starting at 5) with independent
   state, updates, no direct state access, safe cancellation and sibling
   survival. Only then promote guest Java/GraalVM parity and initiate
   LLVM/.NET/Wasm/Forge backend ports.

Filesystem threat model: inherited package traversal rejects internal
symlinks, source escapes, duplicate case-folded IDs, overlapping roots,
invalid UTF-8, oversized sources and changed manifest bytes. It is **not**
race-proof against an adversarial concurrent writer replacing parent path
components while files are opened. Use immutable checkouts or
directory-handle-anchored no-follow reads before hostile multi-tenant use.

## Evidence and review

This remains a stacked draft based on Java #2892 and does not change
`main`. Current private Actions runner jobs have been failing before
assignment (`steps=[]`); issue #963 tracks that incident. Do not consider
host-only tests, source-free canaries or source copies as successful native
Atto guest evidence. Require exact-head executed compiler tests, negative
mutation tests, independent approved review and a clean dependency merge
before enabling guest execution.

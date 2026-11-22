# AttoLang state-indexed actor protocols: nominal compiler admission v1

Status: **DRAFT, source-local declaration preflight only**. No guest runtime
protocol handle, stream continuation, actor-state commit, permission to mint a
reply sidecar, or production full-AOT codegen is implemented by this slice.

## Source model

The compiler recognizes a contiguous root **protocol prelude** (after an
optional flat namespace header). The prelude contains finite states and
`on Message(Payload) -> Successor replies R` or `streams T` transitions.
Normal declarations after the prelude are parsed by the **production Java
Parser** into `Ast.Program`. A protocol after any ordinary declaration, a
nested protocol, and a second protocol after ordinary source are rejected
(`E_PROTOCOL_SCOPE`); protocol declarations are never silently stripped out
of executable bodies.

```atto
define protocol Subscription as
  initial Idle
  state Idle as
    on Connect(Config) -> Active replies Ack
  end
  state Active as
    on Subscribe(Topic) -> Active streams Event
    on Close -> Finished replies Ack
  end
  terminal state Finished
end

define class Config as end
define class Topic as end
define class Ack as end
define class Event as end
define interface EventFeed extends StreamRequest<Event> as end
define class Connect implements UnaryRequest<Ack> as end
define class Subscribe implements EventFeed as end
define class Close implements UnaryRequest<Ack> as end
define actor Client implements Subscription as end
```

**This source is checked and then intentionally rejected** with
`E_UNSUPPORTED_PROTOCOL_LOWERING`. It must not create runnable actor code.

## Compile-time invariants already checked

1. A protocol's state graph is finite and bounded; initial/terminal states,
   transition uniqueness, successor existence, and paths to terminal states
   are checked by `ActorProtocolTypestateV1`.
2. Protocol transitions can only request `UNARY` or `STREAM`. No implicit
   one-way-to-stream conversion is accepted.
3. All message types must resolve to non-generic concrete source-local classes.
   Declared response/item/payload types must be concrete known scalars or
   source-local classes, not `Any`, `void`, aliases, generic message
   declarations or imported unlinked types.
4. A message's nominal mode must arise from actual AST class/interface parent
   type references: `UnaryRequest<R>`, `StreamRequest<T>`, or a transitive
   unambiguous chain. No method return, user string map, or ordinary
   `OneWayMessage` may masquerade as a streaming reply contract.
5. Type-erased markers, unresolved parent declarations, cycles, incompatible
   inherited markers, and duplicate/ambiguous type names fail closed.
6. Protocol actor attachments are derived from parsed actor declarations and
   their actual `implements` references, not incidental source-token text.
7. A state-union send obligation is admitted only when **every possible state**
   permits the same message, response cardinality and response payload type.
   The returned metadata indicates that authority *would* be consumed, but
   does not execute the move or mint an affine capability.

This is a bounded in-file source graph, **not** a package-wide import resolver.
The parser cannot prove named generic specialization, structural payload layout,
actor mailbox admission, or cross-module type conformance here. These remain
explicitly unsupported, rather than returning a false proof.

## Critical future work

- Integrate imported nominal identities and mode markers into the full
  TypeChecker/HIR without permitting user-defined aliases to spoof compiler
  markers; preserve this metadata through optimization, ABI digests and AOT.
- Integrate a CFG-based **affine ProtocolHandle/StreamLease loan model**:
  exactly one owner, no duplicate successor handle, no use-after-`take!`,
  correct union joins, no state loan across `await`/critical callbacks.
- Connect authenticated, generation-fenced actor mailbox admission,
  commit-before-reply, bounded per-direction credits, monotonically ordered
  stream items, exactly one EOF or terminal error, and cancellation/restart
  fencing. Do not confuse request streams with persistent `pipe`/`rpipe`.
- Add client-stream and duplex modes with independent half-close; do not
  equate these with server-streaming or repeated unary request calls.
- Run real guest `.atto` programs and negative compiler fixtures on JVM,
  Native Image/full AOT, LLVM, Wasm, Forge, .NET and further supported targets.
  **No backend can be labeled verified from this parse-only admission.**

## Verification and governance

The focused source-only Java smoke workflow remains separate from actual
Maven/JUnit and guest/AOT evidence. The workflow now requests full JDK25
production compilation and focused JUnit tests, but only **executed CI steps**
on the exact head count as test evidence.

The private-source repository's approved CI destination list is empty.
Cross-organization source transfer is forbidden until its reviewed V3
security policy and live source-main protection requirements are met.

Related: atto-spec #203 (state-indexed protocols), atto-spec #40 (nominal
one-way/unary/stream reply modes), atto-source.java #1708/#1735 (original
stages), atto-source.rs #250 (nominal prototype), and the integration PR #1743.

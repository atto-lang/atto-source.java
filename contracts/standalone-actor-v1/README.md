# Compact actor declarations and guarded named bindings

Status: **authored, NOT EXECUTED**. These are source-level fixtures for
the stacked Java frontend stage-three proposal. They do not certify
actual Java guest execution or compiler/backend parity.

| Fixture | Expected |
| --- | --- |
| `accept_compact_actor.atto` | Java parser/type checker accepts, real guest `main()` returns 42 only if executed |
| `reject_unlowered_named_binding.atto` | Type checker rejects unlowered `Cart as cart;` |
| `reject_unlowered_private_binding.atto` | Type checker rejects unlowered private binding |
| `reject_generic_named_binding.atto` | Parser rejects unsupported generic binding |
| `reject_missing_public_actor.atto` | Type checker rejects private actor contract witness |

`define actor Cart;` is an empty actor CLASS declaration using the
existing scheduler metadata, with no extra instance or mailbox. It is
equivalent to `define actor Cart as end`.

`define actor Cart as cart;` is a new explicit AST representation whose
named binding is **not executable**. The compiler must not silently
erase `cart`, treat it as an `ActorRef`, allow state access, or create
a singleton actor. The type checker and export/incremental ABI gates must
reject it until static owner binding, activation, lifecycle, generation
fencing, receiver-method dispatch, and cancellation semantics exist.

Acceptance criteria for actual execution receipts: exact Git head/tree,
fixture blob hashes, admitted runner with nonzero steps, compiler/guest
versions and exit codes, and positive guest return `42` plus explicit
negative diagnostics. A zero-step GitHub Actions job is not evidence.

Retain `.atto` as canonical, `.ores` as legacy-compatible input.

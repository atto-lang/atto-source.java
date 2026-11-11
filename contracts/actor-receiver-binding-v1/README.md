# Actor receiver nominal binding — static stage 7

This corpus exercises the Java frontend's `ActorReceiverBinder` against
**actual parsed AttoLang source**. A valid binding is *not executable* and
does not create an `ActorContext`, dispatch table, actor instance or witness.

| Fixture | Binder expectation |
| --- | --- |
| `accept_nominal_binding.atto` | Accept one local Worker.get_count/0 slot; ordinary fnc unchanged |
| `reject_missing_actor.atto` | Missing local nominal actor |
| `reject_cross_module_receiver.atto` | Actor declared in another module cannot authorize receiver |
| `reject_duplicate_external_slot.atto` | Same actor + method + arity is ambiguous even if types differ |
| `reject_public_receive.atto` | Scheduler-owned receive hook is private ActorMail<T> → void |
| `reject_in_class_collision.atto` | In-class and external instance methods cannot own same slot |

`ActorReceiverBinderTest` loads these files with the **real Parser** and
asserts binder results. The accepted fixture must still be rejected by the
executable type checker until actor-context/MIR and mailbox lowering exist.

Boundaries: this stage resolves declarations only **within a single
compilation unit's named module**. No cross-file identity is inferred from
the spelling of a module or a package path. A later linker stage must
establish and authenticate a common module/package identity before methods
in different files may be combined. Until then, unresolved external methods
fail closed.

These test cases are *authored but not executed*. No Java guest execution,
NativeAOT run or five-backend parity is claimed.

## Hardening-stage negative additions

- `reject_inherited_actor.atto`: external method on a subclass is rejected
  until inherited receiver slots can be resolved and proven collision-free.
- `reject_borrowed_receive.atto`: borrowed mailbox payloads must not gain
  transport/ownership authority through a binder-only type shape.
- `reject_structural_receive.atto`: structural record payloads cannot be
  mistaken for a validated sendable nominal mailbox payload.

These are static, *non-executable* checks. Passing the binder is not evidence
of sendability, lifecycle safety, or AOT parity.

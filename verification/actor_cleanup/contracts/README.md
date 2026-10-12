# Actor finalization conformance trace v1

This folder contains a machine-readable **prospective** host-owned event
contract in TypeSpec and JSON Schema. It is *not* yet wired to the Java
runtime. A guest cannot issue, replay, or authorize finalization by
emitting these records.

The runtime's emission sequence is expected to refine
`ActorTwoPhaseFinalization.tla`:

- `stop_committed` precedes scheduler/producer shutdown effects.
- `shutdown_effects_settled`, `carrier_quiesced`, and all required
  `child_physical_barrier` events precede `finalization_claimed`.
- Exactly one `finalization_claimed` per actor incarnation.
- `scheduler_closed` and `resources_released` precede
  `physical_termination_published`.
- A terminal event is not a proof of **guest** defer/finally completion.
  Abruptly canceled `SourceTask` guest cleanup remains a separately blocked
  release gate.

JSON Schema validates each event's fields, not ordered-trace refinement.
For the future executable trace verifier, reject duplicate sequence numbers,
foreign actor/incarnation identifiers, missing child barriers, and any
terminal event without all required predecessor events. No runtime trace
conformance results are claimed in this revision.

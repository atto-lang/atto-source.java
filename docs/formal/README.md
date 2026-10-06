# Oreslang Runtime Formal Verification

The runtime lifecycle is modeled as a transition system. The first model covers VM
startup/shutdown and the ownership ordering between the VM, dispatcher, context,
scheduler, task, Future, actor, and channel lifecycles.

## Primary model

RuntimeLifecycle.tla is the normative transition-system model. It abstracts away
Java implementation details and models only lifecycle state and ordering/ownership
constraints.

RuntimeLifecycle.cfg runs the model with two schedulers and two tasks. With TLC,
this is a bounded safety check over the complete finite state space for those bounds.

## Executable regression checker

RuntimeLifecycleModelCheckerTest is a dependency-free explicit-state checker that
mirrors the model's core transition relation and safety invariants. It runs as part
of the normal Maven test suite, so lifecycle invariants remain checked even when TLC
is not installed.

The Java checker is a regression guard, not a replacement for TLC. TLA+/TLC gives
the declarative formal specification and exhaustive model checking; the Java checker
keeps a continuously executable shadow of the safety contract.

## Verification layers

1. VM/dispatcher/context/scheduler lifecycle.
2. Actor supervision and structured cancellation.
3. Future/Promise single-settlement.
4. Channel/select registration and closure races.
5. Turn admission and scheduler fairness/liveness.

Safety and liveness are deliberately separated. The first model checks safety.
Later models will add fairness assumptions and explicit liveness properties.

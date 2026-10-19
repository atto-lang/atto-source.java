# Durable actor execution quiescence — fail-closed handoff (draft)

This is a host/supervisor-only ActorRuntime execution barrier, not yet a durable mailbox implementation or a GraalVM snapshot/restore mechanism.

## Safe admission and freeze protocol

1. A supervisor calls `requestDurableQuiescence(actorRef)` only for an initialized actor. The request serializes against scheduler admission and user-mailbox writes under `ActorCell.lifecycleLock`.
2. Already running guest execution finishes, but no new dispatch is admitted. A `send()` during freeze **throws `ActorSealedException`**. The rejection applies at both the preliminary slot reservation and the final mailbox-write admission, so a sender preparing its payload cannot bypass the freeze.
3. Once the `TurnExecutor` exits, the actor can grant a quiescence proof **only if both its accepted user mailbox and continuation mailbox are empty**, its source receive is not suspended and no owned logical operations remain. If old accepted mail is still queued, the freeze fails closed and scheduling resumes to drain it. Accepted messages are never silently dropped as a successful hibernation side effect.
4. The host-only proof is released with `close()` after an aborted persistence attempt, or consumed with `stopAfterHibernate()` **only after persistence commits**. A successful hibernate stop and a competing resume are serialized by the same actor lifecycle monitor. The caller cannot mutate the authoritative completion future.
5. The freeze does **not** redirect new work to PostgreSQL. Ingress must handle `ActorSealedException` without acknowledging delivery, or use the existing authenticated durable PostgreSQL inbox independently of `ActorRef.send()`. The ingress-to-activation mapping and durable replay are not wired here.

## Boundaries and limitations

- An in-memory mailbox contains no persisted acknowledgment: `send()` success is not a durable delivery guarantee before freeze begins. A truly durable caller must enqueue into the PostgreSQL inbox and obtain a commit receipt before acknowledging external input.
- This proof is not a PostgreSQL activation lease credential, does not serialize actor-owned heaps, and cannot be used as a substitute for the real snapshot codec and schema validation.
- Continuations and suspended receives are **not** made serializable. Instead, the barrier rejects freezing until their logical ownership ends.
- Database I/O must be dispatched off actor and CONTROL carriers. The supervisor must bind `DurableActorStore.Address`, activation owner UUID, execution proof, and exact code generation before hibernation.
- `requires static java.sql` exists to compile optional JDBC integration without imposing a mandatory Java SQL runtime dependency on the Oreslang actor core.

## Regression evidence

Prior exact-source private CI run [#39179146095](https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/actions/runs/39179146095) compiled all 86 Java production sources and passed **five** original `ActorDurableQuiescenceTest` JUnit tests before the mailbox-loss fixes.

The hardened branch adds the rejection-based mailbox handoff and seven tests in total, including stop/resume races and forged observer completion. **These newest changes have not passed full runtime CI yet**: the source Actions jobs execute zero test steps and publication of an updated full-runtime mirror to a private carrier was blocked. Do not treat the earlier five-test run as validation of the new changes.

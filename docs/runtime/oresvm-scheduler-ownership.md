# OresVM scheduler, pool and supervisor ownership contract

Status: Java context-lifecycle ownership implemented; LLVM native host four-domain
ownership substrate implemented separately. This is not proof that native
source-generated actors are wired into the new VM facade.

## Pool topology

One OresVM per OresContext is the root lifecycle authority.

| Domain | Java owner | Default carrier workers | Config |
| --- | --- | ---: | --- |
| MAIN / CONTROL | OresVM rootScheduler + controlExecutor | 5 | ores.runtime.control.parallelism |
| SHARED | OresVM -> ActorRuntime.sharedDispatcher | 5 | ores.runtime.actor.shared.parallelism |
| TRUSTED_PRIVATE | OresVM -> ActorRuntime.privateDispatcher | 5 | ores.runtime.actor.private.parallelism |
| UNTRUSTED | OresVM -> ActorRuntime.untrustedDispatcher | 5 | ores.runtime.actor.untrusted.parallelism |

All worker counts must be positive. Runtime-created per-actor
OresScheduler objects are logical continuation/mailbox dispatchers and
run on the corresponding actor domain's carriers. User-created OresScheduler
objects still own private carrier pools: OresVM does **not** automatically
own all host-created standalone schedulers. An ActorGroup is an ownership and
supervision domain, not a separate OS thread pool.

## Scheduler threading and handoffs

A carrier is an OS thread that executes scheduler turns. Many actors share
each domain's five carriers; no actor is permanently pinned to a carrier.
The root scheduler is one scheduler facade over the CONTROL executor, not five
different OresScheduler objects. Per-actor schedulers use a one-active-turn
mailbox admission authority: **logical single-writer** is not a dedicated
thread. Continuations return to their actor mailbox after await/cooperate,
possibly on a different carrier, and never execute inline on the producer's
I/O or completion thread. A CPU affinity lane can own a physical thread
without creating one physical thread per actor or logical scheduler.

A synchronous blocking host operation may not monopolize a carrier.
Source-level `rt cooperate` requires compiler continuation lowering:
OS `yield`, safepoint polling and watchdog CPU accounting alone cannot
suspend an arbitrary Java/Graal call stack.

## Supervisors

The MAIN/CONTROL domain is the natural placement of root supervisors
(the top of the supervision hierarchy). Child supervisors own and advise
ActorGroups. Each ActorGroup must have a supervisor at admission, and actor
failures report through a reserved priority CONTROL path; never execute the
supervisor callback directly on a panicking child's carrier. This section
describes the **desired source-level supervision integration**; it does not
claim that every `define supervisor` compiler lowering is completed.

## Security boundaries

PRIVATE actors have memory/alias isolation rules enforced by ActorRuntime,
but a private carrier pool is not a Graal polyglot VM isolate. Likewise,
placing every untrusted actor in **one shared Graal isolate** would join
unrelated hostile tenants into the same failure and heap domain. A separate
untrusted carrier pool is useful for CPU bulkheading, but hostile tenants
require separately enforceable sandbox/isolate domains, or sandboxed worker
processes if a hard cross-tenant boundary is required. Graal adversarial
contexts use `SandboxPolicy.UNTRUSTED`, `spawnIsolate(true)` and
`sandbox.MaxThreads=1` at **context creation**; the actor-kind flag
alone does not instantiate a new isolate for each untrusted actor.

The Java runtime cannot forcibly preempt arbitrary stuck native/JNI code.
A watchdog must request cooperative cancellation; a trusted host must
terminate/revoke a distinct isolate/process for hard containment.

## Lifecycle and failure policy

1. Prestart CONTROL before the first guest main turn.
2. Create actor domains and attach one ActorRuntime to the OresVM.
3. Reject a second ActorRuntime attachment or post-close attachments.
4. Close AsyncRuntime before OresVM; OresVM closes actors/ActorGroups and their
   three carrier domains **while CONTROL still accepts teardown**, then
   closes rootScheduler and CONTROL.
5. Report incomplete actor carrier termination rather than silently claiming
   isolation or successful reclamation. No detached actor/carrier may
   outlive its owning runtime without explicit host-managed force cancellation.

Outstanding: per-domain pool metrics, bounded reconfiguration, root-supervisor
source lowering, per-untrusted-tenant sandbox routing, compiler-level scheduler
capability and placement checks, native VM integration with generated source,
and multi-backend fault-injection tests.

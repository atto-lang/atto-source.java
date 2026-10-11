# OresVM scheduler, pool and supervisor ownership contract

Status: Java context-lifecycle ownership implemented; LLVM native host four-domain
ownership substrate implemented separately. This is not proof that native
source-generated actors are wired into the new VM facade.

## Pool topology

One OresVM per OresContext is the root lifecycle authority.

| Domain | Java owner | Default max carrier workers | Config |
| --- | --- | ---: | --- |
| MAIN / CONTROL | OresVM rootScheduler + controlExecutor | 5 | ores.runtime.control.parallelism |
| SHARED | OresVM -> ActorRuntime.sharedDispatcher | 5 | ores.runtime.actor.shared.parallelism |
| TRUSTED_PRIVATE | OresVM -> ActorRuntime.privateDispatcher | 5 | ores.runtime.actor.private.parallelism |
| UNTRUSTED | OresVM -> ActorRuntime.untrustedDispatcher | 5 | ores.runtime.actor.untrusted.parallelism |

All worker counts must be positive. Runtime-created per-actor
OresScheduler objects are logical continuation/mailbox dispatchers and
run on the corresponding actor domain's carriers. User-created OresScheduler
objects still own private carrier pools: OresVM does **not** automatically
own all host-created standalone schedulers. An ActorGroup is a cooperation and optional structured lifecycle domain,
not an OS thread pool or a scheduler. An actor may join multiple cooperation
groups, but its structured lifecycle manager must be unique.

## Scheduler threading and handoffs

A carrier is an OS thread that executes scheduler turns. Many actors share
each domain's five carriers; no actor is permanently pinned to a carrier.
The root scheduler is one scheduler facade over the CONTROL executor, not five
different OresScheduler objects. OresVM eagerly creates four PoolScheduler
metadata facades (CONTROL, SHARED, TRUSTED_PRIVATE, UNTRUSTED), which report
configured carrier limits and created carrier counts without allocating OS
threads. The main CLI caller can host root turns in strict guest contexts. Per-actor schedulers use a one-active-turn
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

1. Create root CONTROL scheduling metadata and three ActorRuntime carrier
   domain descriptors without allocating OS threads.
2. Defer CONTROL pthread/platform-carrier construction until first dispatched
   CONTROL turn. JNI native pools allocate descriptors at construction and
   create pthreads only when the first actor turn is admitted. Once started,
   native compensation carrier slots are allocated as a bounded set; their
   idle retirement requires a separate worker-reclamation mechanism.
3. Create actor domains and attach one ActorRuntime to the OresVM.
4. Reject a second ActorRuntime attachment or post-close attachments.
5. Close AsyncRuntime before OresVM; OresVM closes actors/ActorGroups and their
   three carrier domains **while CONTROL still accepts teardown**, then
   closes rootScheduler and CONTROL.
6. Report incomplete actor carrier termination rather than silently claiming
   isolation or successful reclamation. No detached actor/carrier may
   outlive its owning runtime without explicit host-managed force cancellation.

Outstanding: adaptive native worker-count scaling/idle retirement,
full CLI caller-thread main execution for ordinary contexts, truly pooled
user-created scheduler policies (currently standalone OresSchedulers allocate
private carriers), bounded reconfiguration, root-supervisor
source lowering, per-untrusted-tenant sandbox routing, compiler-level scheduler
capability and placement checks, native VM integration with generated source,
and multi-backend fault-injection tests.

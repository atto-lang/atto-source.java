---------------- MODULE RuntimeLifecycle ----------------
EXTENDS Naturals, TLC

CONSTANTS MaxSchedulers, MaxTasks

VMStates == {"NEW", "STARTING", "STARTED", "FAILED", "CLOSED"}
DispatcherStates == {"DOWN", "STARTING", "UP", "FAILED", "CLOSED"}
ContextStates == {"NEW", "OPEN", "CLOSED", "FAILED"}
SchedulerStates == {"NEW", "RUNNING", "CLOSING", "CLOSED", "FAILED"}
TaskStates == {"NONE", "QUEUED", "RUNNING", "SETTLED"}
FutureStates == {"NEW", "PENDING", "SUCCEEDED", "FAILED", "CANCELLED"}
ActorStates == {"NONE", "CREATED", "RUNNING", "STOPPING", "TERMINATED"}
ChannelStates == {"OPEN", "CLOSED"}

VARIABLES vm, dispatcher, bindingPublished, context, schedulers, tasks, futures, actor, channel

vars == <<vm, dispatcher, bindingPublished, context, schedulers, tasks, futures, actor, channel>>

Init ==
    /\ vm = "NEW"
    /\ dispatcher = "DOWN"
    /\ bindingPublished = FALSE
    /\ context = "NEW"
    /\ schedulers = [i \in 1..MaxSchedulers |-> "NEW"]
    /\ tasks = [i \in 1..MaxTasks |-> "NONE"]
    /\ futures = [i \in 1..MaxTasks |-> "NEW"]
    /\ actor = "NONE"
    /\ channel = "OPEN"

StartVM ==
    /\ vm = "NEW"
    /\ vm' = "STARTING"
    /\ UNCHANGED <<dispatcher, bindingPublished, context, schedulers, tasks, futures, actor, channel>>

StartDispatcher ==
    /\ vm = "STARTING"
    /\ dispatcher = "DOWN"
    /\ dispatcher' = "STARTING"
    /\ UNCHANGED <<vm, bindingPublished, context, schedulers, tasks, futures, actor, channel>>

DispatcherReady ==
    /\ vm = "STARTING"
    /\ dispatcher = "STARTING"
    /\ dispatcher' = "UP"
    /\ vm' = "STARTED"
    /\ UNCHANGED <<bindingPublished, context, schedulers, tasks, futures, actor, channel>>

StartupFailed ==
    /\ vm = "STARTING"
    /\ vm' = "FAILED"
    /\ dispatcher' \in {"FAILED", "CLOSED"}
    /\ UNCHANGED <<bindingPublished, context, schedulers, tasks, futures, actor, channel>>

PublishBinding ==
    /\ vm = "STARTED"
    /\ dispatcher = "UP"
    /\ ~bindingPublished
    /\ bindingPublished' = TRUE
    /\ UNCHANGED <<vm, dispatcher, context, schedulers, tasks, futures, actor, channel>>

CreateContext ==
    /\ vm = "STARTED"
    /\ dispatcher = "UP"
    /\ context = "NEW"
    /\ context' = "OPEN"
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, schedulers, tasks, futures, actor, channel>>

CreateScheduler(i) ==
    /\ i \in 1..MaxSchedulers
    /\ context = "OPEN"
    /\ schedulers[i] = "NEW"
    /\ schedulers' = [schedulers EXCEPT ![i] = "RUNNING"]
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, tasks, futures, actor, channel>>

AdmitTask(i) ==
    /\ i \in 1..MaxTasks
    /\ context = "OPEN"
    /\ \E s \in 1..MaxSchedulers : schedulers[s] = "RUNNING"
    /\ tasks[i] = "NONE"
    /\ tasks' = [tasks EXCEPT ![i] = "QUEUED"]
    /\ futures' = [futures EXCEPT ![i] = "PENDING"]
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, actor, channel>>

RunTask(i) ==
    /\ i \in 1..MaxTasks
    /\ tasks[i] = "QUEUED"
    /\ tasks' = [tasks EXCEPT ![i] = "RUNNING"]
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, futures, actor, channel>>

SettleTask(i) ==
    /\ i \in 1..MaxTasks
    /\ tasks[i] = "RUNNING"
    /\ tasks' = [tasks EXCEPT ![i] = "SETTLED"]
    /\ futures' = [futures EXCEPT ![i] = "SUCCEEDED"]
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, actor, channel>>

CancelFuture(i) ==
    /\ i \in 1..MaxTasks
    /\ futures[i] = "PENDING"
    /\ futures' = [futures EXCEPT ![i] = "CANCELLED"]
    /\ tasks' = [tasks EXCEPT ![i] = "SETTLED"]
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, actor, channel>>

SpawnActor ==
    /\ context = "OPEN"
    /\ \E s \in 1..MaxSchedulers : schedulers[s] = "RUNNING"
    /\ actor = "NONE"
    /\ channel = "OPEN"
    /\ actor' = "CREATED"
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, tasks, futures, channel>>

RunActor ==
    /\ actor = "CREATED"
    /\ actor' = "RUNNING"
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, tasks, futures, channel>>

StopActor ==
    /\ actor \in {"CREATED", "RUNNING"}
    /\ actor' = "STOPPING"
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, tasks, futures, channel>>

TerminateActor ==
    /\ actor = "STOPPING"
    /\ actor' = "TERMINATED"
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, tasks, futures, channel>>

CloseChannel ==
    /\ channel = "OPEN"
    /\ actor \in {"NONE", "TERMINATED"}
    /\ channel' = "CLOSED"
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, schedulers, tasks, futures, actor>>

CloseScheduler(i) ==
    /\ i \in 1..MaxSchedulers
    /\ schedulers[i] = "RUNNING"
    /\ tasks = [j \in 1..MaxTasks |-> "NONE"] \/ \A j \in 1..MaxTasks : tasks[j] = "SETTLED"
    /\ actor \in {"NONE", "TERMINATED"}
    /\ schedulers' = [schedulers EXCEPT ![i] = "CLOSED"]
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, context, tasks, futures, actor, channel>>

CloseContext ==
    /\ context = "OPEN"
    /\ \A i \in 1..MaxSchedulers : schedulers[i] # "RUNNING"
    /\ actor \in {"NONE", "TERMINATED"}
    /\ context' = "CLOSED"
    /\ UNCHANGED <<vm, dispatcher, bindingPublished, schedulers, tasks, futures, actor, channel>>

ShutdownVM ==
    /\ vm \in {"STARTED", "FAILED"}
    /\ context \in {"NEW", "CLOSED", "FAILED"}
    /\ vm' = "CLOSED"
    /\ dispatcher' \in {"CLOSED", "FAILED"}
    /\ bindingPublished' = FALSE
    /\ UNCHANGED <<context, schedulers, tasks, futures, actor, channel>>

Next ==
    \/ StartVM
    \/ StartDispatcher
    \/ DispatcherReady
    \/ StartupFailed
    \/ PublishBinding
    \/ CreateContext
    \/ \E i \in 1..MaxSchedulers : CreateScheduler(i)
    \/ \E i \in 1..MaxTasks : AdmitTask(i)
    \/ \E i \in 1..MaxTasks : RunTask(i)
    \/ \E i \in 1..MaxTasks : SettleTask(i)
    \/ \E i \in 1..MaxTasks : CancelFuture(i)
    \/ SpawnActor
    \/ RunActor
    \/ StopActor
    \/ TerminateActor
    \/ CloseChannel
    \/ \E i \in 1..MaxSchedulers : CloseScheduler(i)
    \/ CloseContext
    \/ ShutdownVM

TypeOK ==
    /\ vm \in VMStates
    /\ dispatcher \in DispatcherStates
    /\ context \in ContextStates
    /\ schedulers \in [1..MaxSchedulers -> SchedulerStates]
    /\ tasks \in [1..MaxTasks -> TaskStates]
    /\ futures \in [1..MaxTasks -> FutureStates]
    /\ actor \in ActorStates
    /\ channel \in ChannelStates
    /\ bindingPublished \in BOOLEAN

Safety ==
    /\ bindingPublished => vm = "STARTED" /\ dispatcher = "UP"
    /\ context = "OPEN" => vm = "STARTED" /\ dispatcher = "UP"
    /\ (\A i \in 1..MaxSchedulers : schedulers[i] = "RUNNING" => context = "OPEN")
    /\ (\A i \in 1..MaxTasks : tasks[i] \in {"QUEUED", "RUNNING"} =>
          context = "OPEN" /\ \E s \in 1..MaxSchedulers : schedulers[s] = "RUNNING")
    /\ (\A i \in 1..MaxTasks : futures[i] \in {"SUCCEEDED", "FAILED", "CANCELLED"} =>
          tasks[i] = "SETTLED")
    /\ actor \in {"CREATED", "RUNNING", "STOPPING"} => context = "OPEN"
    /\ channel = "CLOSED" => actor \notin {"RUNNING", "STOPPING"}

Spec == Init /\ [][Next]_vars

THEOREM TypeSafety == Spec => []TypeOK
THEOREM LifecycleSafety == Spec => []Safety

=============================================================================

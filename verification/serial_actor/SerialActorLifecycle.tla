------------------------ MODULE SerialActorLifecycle ------------------------
EXTENDS Naturals, Sequences, FiniteSets, TLC

\* A finite abstraction of SerialActorAdmission + SerialExecutionTicket.
\* It models one persistent actor, two ingress kinds, and bounded tickets.
\* "STOPPED" is the terminal state until a new incarnation restarts.
CONSTANTS Handlers, Tasks, NoOwner, MaxGen
ASSUME /\ Handlers # {}
       /\ Tasks # {}
       /\ NoOwner \notin Handlers
       /\ MaxGen \in Nat
       /\ MaxGen > 0

VARIABLES phase, owner, pending, outstanding, generation, incarnation,
          tickets, executions
vars == <<phase, owner, pending, outstanding, generation, incarnation,
          tickets, executions>>

Modes == {"IDLE", "RUNNING", "SUSPENDED", "STOPPED"}
TicketNone == [holder |-> NoOwner, gen |-> 0, epoch |-> 0]
IsTicket(t) == tickets[t].holder # NoOwner
TicketCurrent(t) ==
    /\ IsTicket(t)
    /\ owner = tickets[t].holder
    /\ generation = tickets[t].gen
    /\ incarnation = tickets[t].epoch

Init ==
    /\ phase = "IDLE"
    /\ owner = NoOwner
    /\ pending = <<>>
    /\ outstanding = {}
    /\ generation = 0
    /\ incarnation = 0
    /\ tickets = [t \in Tasks |-> TicketNone]
    /\ executions = <<>>

Submit(h) ==
    /\ phase # "STOPPED"
    /\ h \in Handlers \ outstanding
    /\ pending' = Append(pending, h)
    /\ outstanding' = outstanding \cup {h}
    /\ UNCHANGED <<phase, owner, generation, incarnation, tickets, executions>>

Admit ==
    /\ phase = "IDLE"
    /\ Len(pending) > 0
    /\ generation < MaxGen
    /\ owner' = Head(pending)
    /\ pending' = Tail(pending)
    /\ generation' = generation + 1
    /\ phase' = "RUNNING"
    /\ UNCHANGED <<outstanding, incarnation, tickets, executions>>

Capture(t) ==
    /\ phase = "RUNNING"
    /\ t \in Tasks
    /\ ~IsTicket(t)
    /\ tickets' = [tickets EXCEPT ![t] =
        [holder |-> owner, gen |-> generation, epoch |-> incarnation]]
    /\ UNCHANGED <<phase, owner, pending, outstanding, generation,
                   incarnation, executions>>

Suspend ==
    /\ phase = "RUNNING"
    /\ phase' = "SUSPENDED"
    /\ UNCHANGED <<owner, pending, outstanding, generation,
                   incarnation, tickets, executions>>

Resume(t) ==
    /\ phase = "SUSPENDED"
    /\ t \in Tasks
    /\ TicketCurrent(t)
    /\ phase' = "RUNNING"
    /\ UNCHANGED <<owner, pending, outstanding, generation,
                   incarnation, tickets, executions>>

Execute(t) ==
    /\ phase = "RUNNING"
    /\ t \in Tasks
    /\ TicketCurrent(t)
    /\ ~(\E i \in 1..Len(executions) : executions[i].task = t)
    /\ executions' = Append(executions,
        [task |-> t, holder |-> owner, gen |-> generation, epoch |-> incarnation])
    /\ UNCHANGED <<phase, owner, pending, outstanding,
                   generation, incarnation, tickets>>

Complete ==
    /\ phase = "RUNNING"
    /\ outstanding' = outstanding \ {owner}
    /\ phase' = "IDLE"
    /\ owner' = NoOwner
    /\ UNCHANGED <<pending, generation, incarnation, tickets, executions>>

CancelQueued(h) ==
    /\ h \in outstanding
    /\ h # owner
    /\ h \in SeqToSet(pending)
    /\ pending' = SelectSeq(pending, LAMBDA x : x # h)
    /\ outstanding' = outstanding \ {h}
    /\ UNCHANGED <<phase, owner, generation, incarnation, tickets, executions>>

Stop ==
    /\ phase # "STOPPED"
    /\ phase' = "STOPPED"
    /\ owner' = NoOwner
    /\ pending' = <<>>
    /\ outstanding' = {}
    /\ UNCHANGED <<generation, incarnation, tickets, executions>>

Restart ==
    /\ phase = "STOPPED"
    /\ incarnation = 0 \* single bounded restart
    /\ phase' = "IDLE"
    /\ incarnation' = 1
    /\ generation' = 0
    /\ owner' = NoOwner
    /\ UNCHANGED <<pending, outstanding, tickets, executions>>

Next ==
    \/ \E h \in Handlers : Submit(h)
    \/ Admit
    \/ \E t \in Tasks : Capture(t)
    \/ Suspend
    \/ \E t \in Tasks : Resume(t)
    \/ \E t \in Tasks : Execute(t)
    \/ Complete
    \/ \E h \in Handlers : CancelQueued(h)
    \/ Stop
    \/ Restart

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ phase \in Modes
    /\ owner \in Handlers \cup {NoOwner}
    /\ pending \in Seq(Handlers)
    /\ outstanding \subseteq Handlers
    /\ generation \in 0..MaxGen
    /\ incarnation \in 0..1
    /\ tickets \in [Tasks -> [holder : Handlers \cup {NoOwner},
                              gen : 0..MaxGen, epoch : 0..1]]
    /\ executions \in Seq([task : Tasks, holder : Handlers,
                           gen : 0..MaxGen, epoch : 0..1])

SingleHandlerLifetime ==
    /\ (phase \in {"RUNNING", "SUSPENDED"} <=> owner # NoOwner)
    /\ (owner # NoOwner => owner \in outstanding)
    /\ (phase = "STOPPED" => owner = NoOwner /\ pending = <<>>)

FifoAndOwnership ==
    /\ \A i, j \in 1..Len(pending) : i # j => pending[i] # pending[j]
    /\ SeqToSet(pending) \subseteq outstanding
    /\ (owner # NoOwner => owner \notin SeqToSet(pending))

NoForgedExecution ==
    \A i \in 1..Len(executions) :
        LET e == executions[i] IN
            /\ tickets[e.task].holder = e.holder
            /\ tickets[e.task].gen = e.gen
            /\ tickets[e.task].epoch = e.epoch

NoDuplicateTaskExecution ==
    \A i,j \in 1..Len(executions) :
        i # j => executions[i].task # executions[j]

=============================================================================
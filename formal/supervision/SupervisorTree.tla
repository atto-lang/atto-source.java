--------------------------- MODULE SupervisorTree ---------------------------
EXTENDS Naturals, FiniteSets

\* A deliberately small, independently checkable hierarchical shutdown
\* model. Every node has ONE lifecycle FSM, not one global Cartesian FSM.
\* Finite root + two sibling topology, two epochs. For larger models,
\* vary these four definitions and rerun TLC; no real actor count is proven.
CONSTANT Chain
Nodes == {"root", "a", "b"}
Root == "root"
\* Chain=FALSE: root -> {a,b}; Chain=TRUE: root -> a -> b.
Parent == [n \in Nodes \ {Root}
           |-> IF Chain /\ n = "b" THEN "a" ELSE Root]
MaxEpoch == 2

ASSUME /\ Chain \in BOOLEAN
       /\ Nodes # {}
       /\ Root \in Nodes
       /\ Parent \in [Nodes \ {Root} -> Nodes]
       /\ MaxEpoch \in Nat
       /\ MaxEpoch >= 1

VARIABLES phase, epoch, fenced, workerDone, loans, preDone, postDone,
          timedOut

Children(n) == {c \in Nodes \ {Root} : Parent[c] = n}
Done(n) == phase[n] \in {"Stopped", "Reclaimed"}
AllChildrenDone(n) == \A c \in Children(n) : Done(c)
AllChildrenReclaimed(n) ==
    \A c \in Children(n) : phase[c] = "Reclaimed"
vars == <<phase, epoch, fenced, workerDone, loans, preDone, postDone,
          timedOut>>

Phases == {"Running", "Restarting", "Pre", "Children", "Workers",
           "Post", "Stopped", "Reclaimed"}

Init ==
    /\ phase = [n \in Nodes |-> "Running"]
    /\ epoch = [n \in Nodes |-> 1]
    /\ fenced = [n \in Nodes |-> FALSE]
    /\ workerDone = [n \in Nodes |-> FALSE]
    /\ loans = [n \in Nodes |-> 1]
    /\ preDone = [n \in Nodes |-> FALSE]
    /\ postDone = [n \in Nodes |-> FALSE]
    /\ timedOut = [n \in Nodes |-> FALSE]

\* Reaching the shutdown linearization point fences every descendant in
\* ONE transition, even if a restart was already reserved.
BeginShutdown ==
    /\ \A n \in Nodes : phase[n] \in {"Running", "Restarting"}
    /\ phase' = [n \in Nodes |-> "Pre"]
    /\ fenced' = [n \in Nodes |-> TRUE]
    /\ workerDone' = [n \in Nodes |-> FALSE]
    /\ loans' = [n \in Nodes |-> 1]
    /\ UNCHANGED <<epoch, preDone, postDone, timedOut>>

\* A child shutdown hook cannot precede the parent's pre-hook.
PreComplete(n) ==
    /\ phase[n] = "Pre"
    /\ (IF n = Root THEN TRUE
        ELSE phase[Parent[n]] \in
             {"Children", "Workers", "Post", "Stopped", "Reclaimed"})
    /\ phase' = [phase EXCEPT ![n] = "Children"]
    /\ preDone' = [preDone EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<epoch, fenced, workerDone, loans, postDone, timedOut>>

FinishChildren(n) ==
    /\ phase[n] = "Children"
    /\ AllChildrenDone(n)
    /\ phase' = [phase EXCEPT ![n] = "Workers"]
    /\ UNCHANGED <<epoch, fenced, workerDone, loans, preDone, postDone, timedOut>>

\* A future/actor DONE receipt must match its current epoch.
WorkerRetired(n, e) ==
    /\ n \in Nodes
    /\ e = epoch[n]
    /\ ~workerDone[n]
    /\ phase[n] \notin {"Stopped", "Reclaimed"}
    /\ workerDone' = [workerDone EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<phase, epoch, fenced, loans, preDone, postDone, timedOut>>

\* Loan return is a separate physical completion event; DONE alone does
\* not permit arena reclamation. Old-generation returns do not mutate state.
ReturnLoan(n, e) ==
    /\ n \in Nodes
    /\ e = epoch[n]
    /\ workerDone[n]
    /\ loans[n] > 0
    /\ loans' = [loans EXCEPT ![n] = 0]
    /\ UNCHANGED <<phase, epoch, fenced, workerDone, preDone, postDone,
                    timedOut>>

BeginPost(n) ==
    /\ phase[n] = "Workers"
    /\ workerDone[n]
    /\ phase' = [phase EXCEPT ![n] = "Post"]
    /\ UNCHANGED <<epoch, fenced, workerDone, loans, preDone, postDone,
                    timedOut>>

PostComplete(n) ==
    /\ phase[n] = "Post"
    /\ phase' = [phase EXCEPT ![n] = "Stopped"]
    /\ postDone' = [postDone EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<epoch, fenced, workerDone, loans, preDone, timedOut>>

\* Physical cleanup requires both the actor and ALL descendant leases.
Reclaim(n) ==
    /\ phase[n] = "Stopped"
    /\ workerDone[n]
    /\ loans[n] = 0
    /\ AllChildrenReclaimed(n)
    /\ phase' = [phase EXCEPT ![n] = "Reclaimed"]
    /\ UNCHANGED <<epoch, fenced, workerDone, loans, preDone, postDone,
                    timedOut>>

\* Reservation may precede retirement, matching the Java reducer.
\* Only CommitRestart may advance the epoch after actor/resource retirement.
RequestRestart(n) ==
    /\ phase[n] = "Running"
    /\ ~fenced[n]
    /\ epoch[n] < MaxEpoch
    /\ phase' = [phase EXCEPT ![n] = "Restarting"]
    /\ workerDone' = [workerDone EXCEPT ![n] = FALSE]
    /\ loans' = [loans EXCEPT ![n] = 1]
    /\ UNCHANGED <<epoch, fenced, preDone, postDone, timedOut>>

CommitRestart(n) ==
    /\ phase[n] = "Restarting"
    /\ ~fenced[n]
    /\ workerDone[n]
    /\ loans[n] = 0
    /\ epoch' = [epoch EXCEPT ![n] = @ + 1]
    /\ phase' = [phase EXCEPT ![n] = "Running"]
    /\ workerDone' = [workerDone EXCEPT ![n] = FALSE]
    /\ loans' = [loans EXCEPT ![n] = 1]
    /\ UNCHANGED <<fenced, preDone, postDone, timedOut>>

Deadline(n) ==
    /\ n \in Nodes
    /\ fenced[n]
    /\ ~Done(n)
    /\ ~timedOut[n]
    /\ timedOut' = [timedOut EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<phase, epoch, fenced, workerDone, loans, preDone,
                    postDone>>

Next ==
    \/ BeginShutdown
    \/ (\E n \in Nodes : PreComplete(n))
    \/ (\E n \in Nodes : FinishChildren(n))
    \/ (\E n \in Nodes : BeginPost(n))
    \/ (\E n \in Nodes : PostComplete(n))
    \/ (\E n \in Nodes : Reclaim(n))
    \/ (\E n \in Nodes : Deadline(n))
    \/ (\E n \in Nodes : RequestRestart(n) \/ CommitRestart(n))
    \/ (\E n \in Nodes : \E e \in 1..MaxEpoch :
          WorkerRetired(n, e) \/ ReturnLoan(n, e))

Spec == Init /\ [][Next]_vars

TypeOK ==
    /\ phase \in [Nodes -> Phases]
    /\ epoch \in [Nodes -> 1..MaxEpoch]
    /\ fenced \in [Nodes -> BOOLEAN]
    /\ workerDone \in [Nodes -> BOOLEAN]
    /\ loans \in [Nodes -> 0..1]
    /\ preDone \in [Nodes -> BOOLEAN]
    /\ postDone \in [Nodes -> BOOLEAN]
    /\ timedOut \in [Nodes -> BOOLEAN]

FenceSafety ==
    \A n \in Nodes :
        phase[n] \notin {"Running", "Restarting"} => fenced[n]

ShutdownSafety ==
    \A n \in Nodes :
        Done(n) =>
          (fenced[n] /\ preDone[n] /\ postDone[n] /\
           workerDone[n] /\ AllChildrenDone(n))

NoPrematureReclaim ==
    \A n \in Nodes :
        (phase[n] = "Reclaimed") =>
          (loans[n] = 0 /\ AllChildrenReclaimed(n))

NoPostBeforeChildren ==
    \A n \in Nodes :
        phase[n] \in {"Post", "Stopped", "Reclaimed"} =>
          AllChildrenDone(n)

NoRestartAfterFence ==
    \A n \in Nodes :
        fenced[n] => phase[n] \notin {"Running", "Restarting"}

Safety ==
    TypeOK /\ FenceSafety /\ ShutdownSafety /\
    NoPrematureReclaim /\ NoPostBeforeChildren /\ NoRestartAfterFence

=============================================================================

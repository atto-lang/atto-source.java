package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/** Host-only scalar startup orchestration; no guest publication or physical cleanup proof. */
final class ActorSpawnAdmission {
    private final ActorSpawnReservation reservation;

    ActorSpawnAdmission(ActorSpawnReservation.Charge limit) {
        reservation = new ActorSpawnReservation(limit);
    }

    synchronized Admission admit(ActorStartupSchema schema, List<ActorStartupSchema.Supplied> supplied,
            ActorSpawnChargePlan.Policy policy, ActorSpawnChargePlan.Defaults defaults,
            ActorSpawnChargePlan.Request request) {
        Objects.requireNonNull(schema);
        var payload = schema.validate(supplied);
        var plan = ActorSpawnChargePlan.resolve(policy, defaults, request);
        // Construct the wrapper before charging; after reserve only assignment remains.
        var admission = new Admission(this, schema, payload, plan);
        admission.permit = reservation.reserve(plan.charge(), policy.serial() ? 1 : policy.liveStackSlots());
        return admission;
    }

    synchronized InitTicket beginInit(Admission admission) {
        requireOwner(admission);
        var ticket = new InitTicket(admission);
        ticket.lease = reservation.beginInit(admission.permit);
        admission.ticket = ticket;
        return ticket;
    }

    synchronized List<Object> takeStartup(InitTicket ticket) {
        var admission = requireTicket(ticket);
        if (admission.payload == null) throw authority();
        var values = admission.schema.valuesFor(admission.payload);
        admission.payload = null;
        return values;
    }

    synchronized boolean completeInit(InitTicket ticket, boolean success) {
        var admission = requireTicket(ticket);
        boolean ready = reservation.completeInit(ticket.lease, success);
        admission.payload = null;
        admission.ticket = null;
        return ready;
    }

    synchronized boolean mayReceive(Admission admission) {
        requireOwner(admission);
        return reservation.mayReceive(admission.permit);
    }

    synchronized ActorSpawnReservation.ReceiveLease tryEnterReceive(Admission admission) {
        requireOwner(admission);
        return reservation.tryEnterReceive(admission.permit);
    }

    synchronized void retire(Admission admission) {
        requireOwner(admission);
        reservation.retire(admission.permit);
        admission.payload = null;
    }

    /** Caller remains responsible for proving physical quiescence outside this host model. */
    synchronized void releaseAfterQuiescence(Admission admission) {
        requireOwner(admission);
        reservation.releaseAfterQuiescence(admission.permit);
    }

    synchronized ActorSpawnReservation.Usage usage() { return reservation.usage(); }

    private void requireOwner(Admission admission) {
        if (admission == null || admission.owner != this) throw authority();
    }

    private Admission requireTicket(InitTicket ticket) {
        if (ticket == null) throw authority();
        var admission = ticket.admission;
        requireOwner(admission);
        if (admission.ticket != ticket) throw authority();
        return admission;
    }

    private static IllegalStateException authority() { return new IllegalStateException("E_SPAWN_INIT_AUTHORITY"); }

    static final class Admission {
        private final ActorSpawnAdmission owner;
        private final ActorStartupSchema schema;
        private ActorStartupSchema.Payload payload;
        private final ActorSpawnChargePlan.Plan plan;
        private ActorSpawnReservation.Permit permit;
        private InitTicket ticket;
        private Admission(ActorSpawnAdmission owner, ActorStartupSchema schema,
                ActorStartupSchema.Payload payload, ActorSpawnChargePlan.Plan plan) {
            this.owner = owner; this.schema = schema; this.payload = payload; this.plan = plan;
        }
        ActorSpawnChargePlan.Plan plan() { return plan; }
    }

    static final class InitTicket {
        private final Admission admission;
        private ActorSpawnReservation.InitLease lease;
        private InitTicket(Admission admission) { this.admission = admission; }
    }
}

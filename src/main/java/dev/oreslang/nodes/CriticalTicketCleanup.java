package dev.oreslang.nodes;

import dev.oreslang.runtime.CriticalGate;
import dev.oreslang.runtime.OresFuture;
import java.util.Objects;

/**
 * SourceTask unwind helper for an admitted but not-yet-fully-executed critical
 * ticket. Cancellation retracts queued admissions through the Future's
 * runtime-owned hook. A successfully promoted or immediate ticket must instead
 * be released directly. Idempotent for a stale or already-released ticket.
 *
 * Host runtime utility; this does not grant guest critical-source admission.
 */
final class CriticalTicketCleanup {
    private CriticalTicketCleanup() { }

    static void revoke(CriticalGate gate, OresFuture<CriticalGate.Ticket> admission) {
        Objects.requireNonNull(gate, "critical gate");
        Objects.requireNonNull(admission, "critical admission");
        admission.cancel(false);
        if (admission.isDone() && !admission.isCancelled()) {
            final CriticalGate.Ticket ticket;
            try {
                ticket = admission.join();
            } catch (RuntimeException failedAdmission) {
                // Failed/retired requests own no ticket; SourceTask delivers
                // their errors separately through the await continuation.
                return;
            }
            gate.release(ticket);
        }
    }
}

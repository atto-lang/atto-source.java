package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-only bounded transport handoff for a checked actor DAG.
 *
 * This is not guest-language lowering: the scheduler must authenticate the
 * current ActorMail turn and serialize each target's payload independently.
 * A mailbox adapter MUST report ADMITTED only after its DATA publication
 * linearization point and MUST NOT throw after publishing an envelope.
 */
final class ActorPipelineAdmissionBridge {
    private ActorPipelineAdmissionBridge() { }

    enum Admission { ADMITTED, FULL, CLOSED }

    @FunctionalInterface
    interface Mailbox<T> {
        Admission tryAdmit(ActorPipelineRouteSession.MailFrame frame, T message);
    }

    record Receipt(String target, long generation, long flowId,
                   long routeId, Admission status) {
        Receipt {
            Objects.requireNonNull(target);
            Objects.requireNonNull(status);
        }
    }

    record BatchReport(List<Receipt> receipts) {
        BatchReport {
            receipts = List.copyOf(receipts);
        }

        int admitted() {
            return (int) receipts.stream()
                    .filter(r -> r.status() == Admission.ADMITTED).count();
        }

        int rejected() { return receipts.size() - admitted(); }
    }

    /**
     * Allocate branch provenance atomically before any mailbox publication.
     * Per-destination mailbox outcomes are independent: a full or closed edge
     * releases only its own route frame, never a previously admitted sibling.
     *
     * Any adapter exception denotes NO publication for the throwing edge.
     * The remaining unattempted frames are rolled back and the exception
     * propagates; committed earlier branches stay live for correlation.
     */
    static <T> BatchReport forwardAll(
            ActorPipelineRouteSession session,
            ActorPipelineRouteSession.MailFrame source,
            T message,
            Mailbox<? super T> mailbox) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(message);
        Objects.requireNonNull(mailbox);
        return session.withAdmissionBarrier(source, () -> {
        List<ActorPipelineRouteSession.MailFrame> children =
                session.forwardAll(source);
        List<Receipt> outcomes = new ArrayList<>(children.size());
        int finished = 0;
        try {
            for (ActorPipelineRouteSession.MailFrame child : children) {
                Admission admission =
                        Objects.requireNonNull(mailbox.tryAdmit(child, message),
                                "mailbox adapter returned null admission");
                if (admission != Admission.ADMITTED) {
                    session.complete(child);
                }
                outcomes.add(new Receipt(child.receiver(), child.generation(),
                        child.flowId(), child.routeId(), admission));
                ++finished;
            }
            return new BatchReport(outcomes);
        } catch (RuntimeException | Error failure) {
            // The throwing edge and subsequent edges were NOT published,
            // by the mandatory adapter contract. Reclaim their route credits.
            for (int i = finished; i < children.size(); ++i) {
                session.complete(children.get(i));
            }
            throw failure;
        }
        });
    }
}

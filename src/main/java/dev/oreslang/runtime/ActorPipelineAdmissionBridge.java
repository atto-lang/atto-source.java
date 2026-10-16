package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Collections;
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

    /**
     * A fully populated, immutable-to-callers receipt vector. The backing
     * array is owned by forwardAll, and is filled exactly once before this
     * object is returned. Neither adapters nor callers receive that array.
     *
     * Critically, its read-only view is constructed BEFORE publishing the
     * first mailbox envelope: the successful path allocates no reports,
     * receipts or collection wrappers after an irrevocable admission.
     */
    static final class BatchReport {
        private final List<Receipt> receipts;

        private BatchReport(Receipt[] ownedSlots) {
            receipts = Collections.unmodifiableList(Arrays.asList(ownedSlots));
        }

        List<Receipt> receipts() { return receipts; }

        int admitted() {
            int total = 0;
            for (Receipt receipt : receipts) {
                if (receipt.status() == Admission.ADMITTED) ++total;
            }
            return total;
        }

        int rejected() { return receipts.size() - admitted(); }
    }

    /**
     * Allocate branch provenance AND every possible receipt outcome before
     * any mailbox publication. Per-destination outcomes are independent:
     * a full or closed edge releases only its own frame.
     *
     * An adapter exception MUST mean no publication on that edge. Already
     * admitted siblings stay live, while unattempted frames are released.
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
            Receipt[] slots;
            Receipt[][] prepared;
            BatchReport report;
            try {
                slots = new Receipt[children.size()];
                prepared = new Receipt[Admission.values().length][children.size()];
                // Preallocate every outcome and the immutable view; no
                // per-edge Receipt creation follows a successful publication.
                for (int i = 0; i < children.size(); ++i) {
                    var child = children.get(i);
                    for (Admission status : Admission.values()) {
                        prepared[status.ordinal()][i] = new Receipt(
                                child.receiver(), child.generation(),
                                child.flowId(), child.routeId(), status);
                    }
                }
                report = new BatchReport(slots);
            } catch (RuntimeException | Error allocationFailure) {
                for (var unpublished : children)
                    ActorPipelineRouteSession.retireDelivered(unpublished);
                throw allocationFailure;
            }

            int finished = 0;
            try {
                for (int i = 0; i < children.size(); ++i) {
                    var child = children.get(i);
                    Admission status = Objects.requireNonNull(
                            mailbox.tryAdmit(child, message),
                            "mailbox adapter returned null admission");
                    if (status != Admission.ADMITTED)
                        session.complete(child);
                    slots[i] = prepared[status.ordinal()][i];
                    finished = i + 1;
                }
                return report;
            } catch (RuntimeException | Error failure) {
                // Only this edge and later ones were not published under
                // the mandatory adapter contract. Do not retract any
                // previously admitted siblings.
                for (int i = finished; i < children.size(); ++i)
                    ActorPipelineRouteSession.retireDelivered(children.get(i));
                throw failure;
            }
        });
    }
}

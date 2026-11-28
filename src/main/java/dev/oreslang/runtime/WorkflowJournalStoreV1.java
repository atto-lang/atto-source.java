package dev.oreslang.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Durable-store contract for the v1 workflow journal/outbox semantics.
 *
 * <p>Implementations must preserve the same transition, causality, lease,
 * ambiguity, retry-authority and replay behavior as {@link WorkflowJournalV1}.
 * The in-memory implementation is the executable semantic reference; persistent
 * adapters must fail closed rather than inventing alternate state transitions.</p>
 */
public interface WorkflowJournalStoreV1 extends AutoCloseable {

    record ReadView(
            WorkflowJournalV1.StoreSnapshot storeSnapshot,
            DurableDevLoopV1.ReplayResult replay,
            List<DurableDevLoopV1.JournalEntry> journal,
            List<WorkflowJournalV1.EffectSnapshot> effects) {
        public ReadView {
            java.util.Objects.requireNonNull(storeSnapshot, "storeSnapshot");
            java.util.Objects.requireNonNull(replay, "replay");
            journal = List.copyOf(
                    java.util.Objects.requireNonNull(journal, "journal"));
            effects = List.copyOf(
                    java.util.Objects.requireNonNull(effects, "effects"));
            if (storeSnapshot.journalEntries() != journal.size()) {
                throw new IllegalArgumentException(
                        "read view journal count mismatch");
            }
            if (storeSnapshot.nextSequence() != journal.size() + 1L) {
                throw new IllegalArgumentException(
                        "read view sequence mismatch");
            }
            if (!replay.machine().journal().equals(journal)) {
                throw new IllegalArgumentException(
                        "read view replay/journal mismatch");
            }
        }
    }

    void append(DurableDevLoopV1.Transition transition);

    Optional<WorkflowJournalV1.Claim> claimNext(
            String workerId,
            long nowMillis,
            long leaseMillis);

    void releaseUnexecuted(
            String effectKey,
            String claimToken,
            String reasonRef,
            long nowMillis);

    void complete(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis);

    DurableDevLoopV1.Transition completeAndAppend(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis,
            String eventId,
            DurableDevLoopV1.Event event);

    int expireClaims(long nowMillis);

    void resolveAmbiguousAsCompleted(
            String effectKey,
            String receiptRef,
            String reconciliationRef);

    void resolveAmbiguousForRetry(
            String effectKey,
            String reconciliationRef);

    List<DurableDevLoopV1.JournalEntry> journal();

    List<WorkflowJournalV1.EffectSnapshot> effects();

    Optional<WorkflowJournalV1.EffectSnapshot> effect(String effectKey);

    WorkflowJournalV1.StoreSnapshot snapshot();

    DurableDevLoopV1.ReplayResult replayDevLoop();

    ReadView readView();

    @Override
    default void close() {
        // In-memory implementations own no external resource.
    }
}

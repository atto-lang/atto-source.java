package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory reference journal/outbox for durable workflow semantics.
 *
 * <p>This is not a persistence backend. It defines the fail-closed transaction,
 * lease, ambiguity and reconciliation rules that a PostgreSQL/SQLite/native
 * durable adapter must preserve.</p>
 *
 * <p>Appending a transition and its emitted commands is one synchronized
 * transaction. Effects are keyed by the stable command effect key. A claimed
 * effect whose lease expires becomes AMBIGUOUS and is never automatically
 * re-issued: a supervisor must reconcile the remote system and explicitly mark
 * the effect completed or safe to retry.</p>
 */
public final class WorkflowJournalV1 {

    public enum EffectState {
        PENDING,
        CLAIMED,
        AMBIGUOUS,
        COMPLETED
    }

    public record EffectSnapshot(
            String effectKey,
            DurableDevLoopV1.Command command,
            EffectState state,
            int attempts,
            String workerId,
            String claimToken,
            long leaseUntilMillis,
            String receiptRef,
            String reconciliationRef) { }

    public record Claim(
            String effectKey,
            DurableDevLoopV1.Command command,
            String claimToken,
            int attempt,
            long leaseUntilMillis) { }

    public record StoreSnapshot(
            String runId,
            Integer maxIterations,
            long nextSequence,
            int journalEntries,
            int pendingEffects,
            int claimedEffects,
            int ambiguousEffects,
            int completedEffects) { }

    private static final long MAX_LEASE_MILLIS =
            7L * 24L * 60L * 60L * 1000L;

    private static final class MutableEffect {
        private final String effectKey;
        private final DurableDevLoopV1.Command command;

        private EffectState state = EffectState.PENDING;
        private int attempts;
        private String workerId;
        private String claimToken;
        private long leaseUntilMillis;
        private String receiptRef;
        private String reconciliationRef;

        private MutableEffect(
                String effectKey,
                DurableDevLoopV1.Command command) {
            this.effectKey = effectKey;
            this.command = command;
        }

        private EffectSnapshot snapshot() {
            return new EffectSnapshot(
                    effectKey,
                    command,
                    state,
                    attempts,
                    workerId,
                    claimToken,
                    leaseUntilMillis,
                    receiptRef,
                    reconciliationRef);
        }
    }

    private final List<DurableDevLoopV1.JournalEntry> journal =
            new ArrayList<>();
    private final LinkedHashMap<String, MutableEffect> effects =
            new LinkedHashMap<>();

    private String runId;
    private Integer maxIterations;
    private long nextSequence = 1L;
    private long nextClaimNumber = 1L;
    private long lastObservedMillis = -1L;

    /**
     * Atomically append one reducer transition and register all emitted effects.
     *
     * <p>The reducer snapshot is used as a cross-check, not as the stored source
     * of truth. Recovery replays the stored journal.</p>
     */
    public synchronized void append(DurableDevLoopV1.Transition transition) {
        Objects.requireNonNull(transition, "transition");
        var entry = transition.journalEntry();
        var snapshot = transition.snapshot();
        var commands = transition.commands();

        String incomingRunId = requireText(
                snapshot.runId(), "runId", 512);
        if (runId != null && !runId.equals(incomingRunId)) {
            throw new IllegalArgumentException(
                    "journal cannot mix workflow run ids");
        }
        int incomingMaxIterations = snapshot.maxIterations();
        if (incomingMaxIterations <= 0 || incomingMaxIterations > 10_000) {
            throw new IllegalArgumentException(
                    "snapshot maxIterations must be in [1, 10000]");
        }
        if (maxIterations != null
                && maxIterations.intValue() != incomingMaxIterations) {
            throw new IllegalArgumentException(
                    "journal cannot change workflow iteration budget");
        }

        if (entry.sequence() != nextSequence) {
            throw new IllegalStateException(
                    "journal sequence mismatch: expected "
                            + nextSequence
                            + " but received "
                            + entry.sequence());
        }
        if (snapshot.nextSequence()
                != Math.addExact(entry.sequence(), 1L)) {
            throw new IllegalStateException(
                    "reducer snapshot nextSequence does not match entry");
        }
        if (snapshot.journalEntries() != entry.sequence()) {
            throw new IllegalStateException(
                    "reducer snapshot journal count does not match entry");
        }

        if (entry.disposition() != DurableDevLoopV1.Disposition.APPLIED
                && !commands.isEmpty()) {
            throw new IllegalStateException(
                    "non-applied transition emitted side effects");
        }

        // Prevalidate every command before mutating the store so append remains
        // all-or-nothing.
        Map<String, DurableDevLoopV1.Command> newEffects =
                new LinkedHashMap<>();
        for (var command : commands) {
            Objects.requireNonNull(command, "command");
            String key = requireText(
                    command.effectKey(), "effectKey", 2_048);

            DurableDevLoopV1.Command duplicate =
                    newEffects.putIfAbsent(key, command);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "transition emitted duplicate effect key " + key);
            }

            MutableEffect existing = effects.get(key);
            if (existing != null) {
                throw new IllegalStateException(
                        "stable effect key already has an originating transition: "
                                + key);
            }
        }

        long followingSequence = Math.addExact(nextSequence, 1L);
        if (runId == null) {
            runId = incomingRunId;
        }
        if (maxIterations == null) {
            maxIterations = incomingMaxIterations;
        }
        journal.add(entry);
        nextSequence = followingSequence;
        for (var effect : newEffects.entrySet()) {
            effects.putIfAbsent(
                    effect.getKey(),
                    new MutableEffect(
                            effect.getKey(), effect.getValue()));
        }
    }

    /**
     * Claim the oldest pending effect after first converting expired claims to
     * AMBIGUOUS. Ambiguous effects are intentionally skipped until reconciled.
     */
    public synchronized Optional<Claim> claimNext(
            String workerId,
            long nowMillis,
            long leaseMillis) {
        workerId = requireText(workerId, "workerId", 512);
        requireClock(nowMillis);
        if (leaseMillis <= 0 || leaseMillis > MAX_LEASE_MILLIS) {
            throw new IllegalArgumentException(
                    "leaseMillis must be in (0, "
                            + MAX_LEASE_MILLIS + "]");
        }
        observeClockLocked(nowMillis);

        expireClaimsLocked(nowMillis);

        for (MutableEffect effect : effects.values()) {
            if (effect.state != EffectState.PENDING) continue;

            int nextAttempt = Math.addExact(effect.attempts, 1);
            long claimNumber = nextClaimNumber;
            long followingClaimNumber =
                    Math.addExact(nextClaimNumber, 1L);
            long leaseUntil =
                    Math.addExact(nowMillis, leaseMillis);
            String token =
                    "workflow/" + runId
                            + "/claim/" + claimNumber;

            effect.attempts = nextAttempt;
            effect.state = EffectState.CLAIMED;
            effect.workerId = workerId;
            effect.claimToken = token;
            effect.leaseUntilMillis = leaseUntil;
            nextClaimNumber = followingClaimNumber;

            return Optional.of(new Claim(
                    effect.effectKey,
                    effect.command,
                    token,
                    nextAttempt,
                    leaseUntil));
        }

        return Optional.empty();
    }

    /**
     * Requeue a claimed effect only when the worker positively knows that the
     * remote side effect was never started.
     */
    public synchronized void releaseUnexecuted(
            String effectKey,
            String claimToken,
            String reasonRef) {
        MutableEffect effect =
                requireClaimed(effectKey, claimToken);
        effect.reconciliationRef = requireText(
                reasonRef, "reasonRef", 4_096);
        effect.state = EffectState.PENDING;
        clearClaim(effect);
    }

    /**
     * Complete a currently claimed effect and bind it to a durable receipt.
     */
    public synchronized void complete(
            String effectKey,
            String claimToken,
            String receiptRef) {
        MutableEffect effect =
                requireClaimed(effectKey, claimToken);
        effect.receiptRef = requireText(
                receiptRef, "receiptRef", 8_192);
        effect.state = EffectState.COMPLETED;
        clearClaim(effect);
    }

    /**
     * Run expiry processing without claiming another effect.
     *
     * <p>This is useful for a supervisor/timer actor. Expiry never means retry:
     * the state becomes AMBIGUOUS.</p>
     */
    public synchronized int expireClaims(long nowMillis) {
        requireClock(nowMillis);
        observeClockLocked(nowMillis);
        return expireClaimsLocked(nowMillis);
    }

    /**
     * Reconciliation proved the ambiguous remote operation completed.
     */
    public synchronized void resolveAmbiguousAsCompleted(
            String effectKey,
            String receiptRef,
            String reconciliationRef) {
        MutableEffect effect =
                requireState(effectKey, EffectState.AMBIGUOUS);
        String receipt = requireText(
                receiptRef, "receiptRef", 8_192);
        String reconciliation = requireText(
                reconciliationRef, "reconciliationRef", 8_192);
        effect.receiptRef = receipt;
        effect.reconciliationRef = reconciliation;
        effect.state = EffectState.COMPLETED;
        clearClaim(effect);
    }

    /**
     * Reconciliation proved the ambiguous operation did not happen and is safe
     * to retry with the same stable effect key.
     */
    public synchronized void resolveAmbiguousForRetry(
            String effectKey,
            String reconciliationRef) {
        MutableEffect effect =
                requireState(effectKey, EffectState.AMBIGUOUS);
        effect.reconciliationRef = requireText(
                reconciliationRef, "reconciliationRef", 8_192);
        effect.state = EffectState.PENDING;
        clearClaim(effect);
    }

    public synchronized List<DurableDevLoopV1.JournalEntry> journal() {
        return List.copyOf(journal);
    }

    public synchronized List<EffectSnapshot> effects() {
        return effects.values().stream()
                .map(MutableEffect::snapshot)
                .toList();
    }

    public synchronized Optional<EffectSnapshot> effect(
            String effectKey) {
        effectKey = requireText(effectKey, "effectKey", 2_048);
        MutableEffect effect = effects.get(effectKey);
        return effect == null
                ? Optional.empty()
                : Optional.of(effect.snapshot());
    }

    public synchronized StoreSnapshot snapshot() {
        int pending = 0;
        int claimed = 0;
        int ambiguous = 0;
        int completed = 0;
        for (MutableEffect effect : effects.values()) {
            switch (effect.state) {
                case PENDING -> pending++;
                case CLAIMED -> claimed++;
                case AMBIGUOUS -> ambiguous++;
                case COMPLETED -> completed++;
            }
        }

        return new StoreSnapshot(
                runId,
                maxIterations,
                nextSequence,
                journal.size(),
                pending,
                claimed,
                ambiguous,
                completed);
    }

    /**
     * Rebuild a dev-loop reducer from the durable journal.
     */
    public synchronized DurableDevLoopV1.ReplayResult replayDevLoop() {
        if (runId == null || maxIterations == null) {
            throw new IllegalStateException(
                    "cannot replay an empty workflow journal");
        }
        return DurableDevLoopV1.replay(
                runId, maxIterations, List.copyOf(journal));
    }

    private void observeClockLocked(long nowMillis) {
        if (nowMillis < lastObservedMillis) {
            throw new IllegalArgumentException(
                    "workflow journal clock must not move backwards");
        }
        lastObservedMillis = nowMillis;
    }

    private int expireClaimsLocked(long nowMillis) {
        int expired = 0;
        for (MutableEffect effect : effects.values()) {
            if (effect.state == EffectState.CLAIMED
                    && effect.leaseUntilMillis <= nowMillis) {
                effect.state = EffectState.AMBIGUOUS;
                expired++;
            }
        }
        return expired;
    }

    private MutableEffect requireClaimed(
            String effectKey,
            String claimToken) {
        effectKey = requireText(effectKey, "effectKey", 2_048);
        claimToken = requireText(
                claimToken, "claimToken", 2_048);
        MutableEffect effect =
                requireState(effectKey, EffectState.CLAIMED);
        if (!claimToken.equals(effect.claimToken)) {
            throw new IllegalStateException(
                    "stale or foreign effect claim token");
        }
        return effect;
    }

    private MutableEffect requireState(
            String effectKey,
            EffectState requiredState) {
        effectKey = requireText(effectKey, "effectKey", 2_048);
        MutableEffect effect = effects.get(effectKey);
        if (effect == null) {
            throw new IllegalArgumentException(
                    "unknown effect key " + effectKey);
        }
        if (effect.state != requiredState) {
            throw new IllegalStateException(
                    "effect " + effectKey
                            + " is " + effect.state
                            + ", expected " + requiredState);
        }
        return effect;
    }

    private static void clearClaim(MutableEffect effect) {
        effect.workerId = null;
        effect.claimToken = null;
        effect.leaseUntilMillis = 0L;
    }

    private static void requireClock(long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException(
                    "nowMillis must be non-negative");
        }
    }

    private static String requireText(
            String value,
            String name,
            int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum length " + maxLength);
        }
        return normalized;
    }
}

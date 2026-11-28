package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
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
public final class WorkflowJournalV1 implements WorkflowJournalStoreV1 {

    public enum EffectState {
        PENDING,
        CLAIMED,
        AMBIGUOUS,
        COMPLETED,
        ABANDONED
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
            String reconciliationRef,
            boolean retryAllowed) { }

    public record Claim(
            String effectKey,
            DurableDevLoopV1.Command command,
            String claimToken,
            int attempt,
            long leaseUntilMillis,
            String workerId) {
        public Claim {
            effectKey = requireText(effectKey, "effectKey", 2_048);
            command = Objects.requireNonNull(command, "command");
            if (!effectKey.equals(command.effectKey())) {
                throw new IllegalArgumentException(
                        "claim effect key must match command effect key");
            }
            claimToken = requireText(
                    claimToken, "claimToken", 2_048);
            if (attempt <= 0) {
                throw new IllegalArgumentException(
                        "claim attempt must be positive");
            }
            if (leaseUntilMillis <= 0) {
                throw new IllegalArgumentException(
                        "claim lease deadline must be positive");
            }
            if (workerId != null) {
                workerId = requireText(
                        workerId, "workerId", 512);
            }
        }

        public Claim(
                String effectKey,
                DurableDevLoopV1.Command command,
                String claimToken,
                int attempt,
                long leaseUntilMillis) {
            this(
                    effectKey,
                    command,
                    claimToken,
                    attempt,
                    leaseUntilMillis,
                    null);
        }
    }

    public record StoreSnapshot(
            String runId,
            Integer maxIterations,
            long nextSequence,
            int journalEntries,
            int pendingEffects,
            int claimedEffects,
            int ambiguousEffects,
            int completedEffects,
            int abandonedEffects) { }

    /**
     * Complete serializable state required to reconstruct the reference store.
     * Persistent adapters should encode this data explicitly rather than using
     * Java object serialization.
     */
    public record PersistentState(
            String runId,
            Integer maxIterations,
            long nextSequence,
            long nextClaimNumber,
            long lastObservedMillis,
            List<DurableDevLoopV1.JournalEntry> journal,
            List<EffectSnapshot> effects) {
        public PersistentState {
            journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
            effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
        }
    }

    private static final long MAX_LEASE_MILLIS =
            7L * 24L * 60L * 60L * 1000L;
    private static final SecureRandom CLAIM_RANDOM = new SecureRandom();
    private static final int CLAIM_NONCE_BYTES = 32;

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
        private boolean retryAllowed = true;

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
                    reconciliationRef,
                    retryAllowed);
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

        verifyReducerTransitionLocked(
                transition, incomingRunId, incomingMaxIterations);

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

        prevalidateCausalEffectLocked(
                entry, incomingRunId, snapshot.iteration());

        long followingSequence = Math.addExact(nextSequence, 1L);
        if (runId == null) {
            runId = incomingRunId;
        }
        if (maxIterations == null) {
            maxIterations = incomingMaxIterations;
        }
        journal.add(entry);
        nextSequence = followingSequence;

        if (entry.disposition() == DurableDevLoopV1.Disposition.APPLIED) {
            retireCausalEffectLocked(
                    entry.event(), incomingRunId, snapshot.iteration());
            if (isTerminal(snapshot.phase())) {
                abandonPendingEffectsLocked();
            }
        }

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
        long leaseUntil =
                Math.addExact(nowMillis, leaseMillis);
        observeClockLocked(nowMillis);

        expireClaimsLocked(nowMillis);

        for (MutableEffect effect : effects.values()) {
            if (effect.state != EffectState.PENDING) continue;

            int nextAttempt = Math.addExact(effect.attempts, 1);
            long claimNumber = nextClaimNumber;
            long followingClaimNumber =
                    Math.addExact(nextClaimNumber, 1L);
            String token = claimToken(
                    runId,
                    effect.effectKey,
                    nextAttempt,
                    claimNumber);

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
                    leaseUntil,
                    workerId));
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
            String reasonRef,
            long nowMillis) {
        String reason = requireText(
                reasonRef, "reasonRef", 4_096);
        MutableEffect effect =
                requireLiveClaim(effectKey, claimToken, nowMillis);
        effect.reconciliationRef = reason;
        effect.state = effect.retryAllowed
                ? EffectState.PENDING
                : EffectState.ABANDONED;
        clearClaim(effect);
    }

    /**
     * Complete a currently claimed effect and bind it to a durable receipt.
     */
    public synchronized void complete(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis) {
        String receipt = requireText(
                receiptRef, "receiptRef", 8_192);
        MutableEffect effect =
                requireLiveClaim(effectKey, claimToken, nowMillis);
        effect.receiptRef = receipt;
        effect.state = EffectState.COMPLETED;
        clearClaim(effect);
    }

    /**
     * Atomically bind a successful effect receipt to its causal workflow event.
     *
     * <p>This closes the crash window between effect completion and event
     * delivery. The operation is fail-atomic in the in-memory reference model:
     * all validation/mutation occurs on a verified clone and this store adopts
     * the clone only after both lifecycle operations succeed.</p>
     *
     * <p>If the event was already durably applied and this effect is still
     * claimed, the operation repairs the inverse legacy split by completing the
     * effect without appending another event. If both effect and event are
     * already durable, it is an idempotent no-op that returns the original
     * applied transition.</p>
     */
    public synchronized DurableDevLoopV1.Transition completeAndAppend(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis,
            String eventId,
            DurableDevLoopV1.Event event) {
        effectKey = requireText(effectKey, "effectKey", 2_048);
        claimToken = requireText(claimToken, "claimToken", 2_048);
        receiptRef = requireText(receiptRef, "receiptRef", 8_192);
        eventId = requireText(eventId, "eventId", 512);
        event = Objects.requireNonNull(event, "event");

        WorkflowJournalV1 candidate = restore(persistentState());
        MutableEffect effect = candidate.effects.get(effectKey);
        if (effect == null) {
            throw new IllegalArgumentException(
                    "unknown effect key " + effectKey);
        }

        DurableDevLoopV1.Transition existing =
                candidate.findAppliedTransition(eventId, event);
        if (effect.state == EffectState.COMPLETED) {
            if (!Objects.equals(effect.receiptRef, receiptRef)) {
                throw new IllegalStateException(
                        "completed effect receipt does not match retry");
            }
            if (existing == null) {
                throw new IllegalStateException(
                        "completed effect lacks durable causal outcome");
            }
            candidate.requireMatchingCausalEffect(
                    effectKey, existing);
            return existing;
        }

        DurableDevLoopV1 machine =
                candidate.replayDevLoop().machine();
        DurableDevLoopV1.Transition transition =
                machine.accept(eventId, event);

        if (transition.journalEntry().disposition()
                == DurableDevLoopV1.Disposition.DUPLICATE) {
            if (existing == null) {
                throw new IllegalStateException(
                        "duplicate outcome lacks original applied transition");
            }
            candidate.requireMatchingCausalEffect(
                    effectKey, existing);
            candidate.complete(
                    effectKey,
                    claimToken,
                    receiptRef,
                    nowMillis);
            replaceWith(candidate);
            return existing;
        }

        if (transition.journalEntry().disposition()
                != DurableDevLoopV1.Disposition.APPLIED) {
            throw new IllegalStateException(
                    "successful effect outcome did not produce an applied "
                            + "workflow transition: "
                            + transition.journalEntry().disposition());
        }

        candidate.requireMatchingCausalEffect(
                effectKey, transition);
        candidate.complete(
                effectKey,
                claimToken,
                receiptRef,
                nowMillis);
        candidate.append(transition);
        replaceWith(candidate);
        return transition;
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
        effect.state = effect.retryAllowed
                ? EffectState.PENDING
                : EffectState.ABANDONED;
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
        int abandoned = 0;
        for (MutableEffect effect : effects.values()) {
            switch (effect.state) {
                case PENDING -> pending++;
                case CLAIMED -> claimed++;
                case AMBIGUOUS -> ambiguous++;
                case COMPLETED -> completed++;
                case ABANDONED -> abandoned++;
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
                completed,
                abandoned);
    }

    public synchronized PersistentState persistentState() {
        return new PersistentState(
                runId,
                maxIterations,
                nextSequence,
                nextClaimNumber,
                lastObservedMillis,
                List.copyOf(journal),
                effects());
    }

    /**
     * Restore a journal from explicitly decoded persistent state and re-verify
     * every journal transition before applying persisted effect lifecycle data.
     */
    public static WorkflowJournalV1 restore(PersistentState state) {
        Objects.requireNonNull(state, "state");
        if (state.runId() == null) {
            if (state.maxIterations() != null
                    || state.nextSequence() != 1L
                    || state.nextClaimNumber() != 1L
                    || state.lastObservedMillis() < -1L
                    || !state.journal().isEmpty()
                    || !state.effects().isEmpty()) {
                throw new IllegalStateException(
                        "empty persisted workflow state is internally inconsistent");
            }
            WorkflowJournalV1 empty = new WorkflowJournalV1();
            empty.lastObservedMillis = state.lastObservedMillis();
            return empty;
        }
        if (state.maxIterations() == null) {
            throw new IllegalStateException(
                    "persisted workflow run lacks iteration policy");
        }

        DurableDevLoopV1 machine =
                new DurableDevLoopV1(state.runId(), state.maxIterations());
        WorkflowJournalV1 restored = new WorkflowJournalV1();
        for (DurableDevLoopV1.JournalEntry recorded : state.journal()) {
            DurableDevLoopV1.Transition transition =
                    machine.accept(recorded.eventId(), recorded.event());
            if (!transition.journalEntry().equals(recorded)) {
                throw new IllegalStateException(
                        "persisted journal failed deterministic replay at sequence "
                                + recorded.sequence());
            }
            restored.append(transition);
        }

        if (restored.nextSequence != state.nextSequence()) {
            throw new IllegalStateException(
                    "persisted nextSequence does not match reconstructed journal");
        }

        Map<String, EffectSnapshot> persistedEffects =
                new LinkedHashMap<>();
        for (EffectSnapshot snapshot : state.effects()) {
            EffectSnapshot prior =
                    persistedEffects.putIfAbsent(snapshot.effectKey(), snapshot);
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate persisted effect key " + snapshot.effectKey());
            }
        }
        List<String> reconstructedEffectOrder =
                List.copyOf(restored.effects.keySet());
        List<String> persistedEffectOrder = state.effects().stream()
                .map(EffectSnapshot::effectKey)
                .toList();
        if (!reconstructedEffectOrder.equals(persistedEffectOrder)) {
            throw new IllegalStateException(
                    "persisted effect order does not match reconstructed workflow");
        }

        HashSet<Long> activeClaimNumbers = new HashSet<>();
        for (Map.Entry<String, MutableEffect> entry
                : restored.effects.entrySet()) {
            EffectSnapshot persisted = persistedEffects.get(entry.getKey());
            MutableEffect effect = entry.getValue();
            if (!effect.command.equals(persisted.command())) {
                throw new IllegalStateException(
                        "persisted effect command mismatch for "
                                + entry.getKey());
            }
            validatePersistedEffect(
                    persisted,
                    state.runId(),
                    state.nextClaimNumber());
            if ((persisted.state() == EffectState.CLAIMED
                    || persisted.state() == EffectState.AMBIGUOUS)
                    && !activeClaimNumbers.add(
                            claimNumberFromToken(persisted.claimToken()))) {
                throw new IllegalStateException(
                        "duplicate active persisted claim number");
            }
            if (effect.retryAllowed != persisted.retryAllowed()) {
                throw new IllegalStateException(
                        "persisted retry authority disagrees with "
                                + "journal-derived authority for "
                                + entry.getKey());
            }
            effect.state = persisted.state();
            effect.attempts = persisted.attempts();
            effect.workerId = persisted.workerId();
            effect.claimToken = persisted.claimToken();
            effect.leaseUntilMillis = persisted.leaseUntilMillis();
            effect.receiptRef = persisted.receiptRef();
            effect.reconciliationRef = persisted.reconciliationRef();
            effect.retryAllowed = persisted.retryAllowed();
        }

        if (!Objects.equals(restored.runId, state.runId())
                || !Objects.equals(
                        restored.maxIterations, state.maxIterations())) {
            throw new IllegalStateException(
                    "persisted run identity/policy mismatch after reconstruction");
        }
        long totalAttempts = 0L;
        for (EffectSnapshot effect : state.effects()) {
            totalAttempts = Math.addExact(
                    totalAttempts, effect.attempts());
        }
        long expectedNextClaimNumber =
                Math.addExact(totalAttempts, 1L);
        if (state.nextClaimNumber() != expectedNextClaimNumber) {
            throw new IllegalStateException(
                    "persisted nextClaimNumber "
                            + state.nextClaimNumber()
                            + " does not match issued claim count "
                            + totalAttempts);
        }
        if (state.lastObservedMillis() < -1L) {
            throw new IllegalStateException(
                    "persisted lastObservedMillis is invalid");
        }
        restored.nextClaimNumber = state.nextClaimNumber();
        restored.lastObservedMillis = state.lastObservedMillis();
        return restored;
    }

    private static void validatePersistedEffect(
            EffectSnapshot effect,
            String runId,
            long nextClaimNumber) {
        Objects.requireNonNull(effect.effectKey(), "effectKey");
        Objects.requireNonNull(effect.command(), "command");
        Objects.requireNonNull(effect.state(), "state");
        if (effect.attempts() < 0) {
            throw new IllegalStateException(
                    "persisted effect attempts cannot be negative");
        }
        switch (effect.state()) {
            case CLAIMED -> {
                if (effect.attempts() <= 0
                        || effect.workerId() == null
                        || effect.claimToken() == null
                        || effect.leaseUntilMillis() <= 0) {
                    throw new IllegalStateException(
                            "claimed persisted effect lacks live claim metadata");
                }
                validateClaimToken(
                        effect,
                        runId,
                        nextClaimNumber);
            }
            case PENDING, COMPLETED, ABANDONED -> {
                if (effect.workerId() != null
                        || effect.claimToken() != null
                        || effect.leaseUntilMillis() != 0L) {
                    throw new IllegalStateException(
                            effect.state()
                                    + " persisted effect retained claim metadata");
                }
            }
            case AMBIGUOUS -> {
                // Ambiguity deliberately retains the expired claim identity so
                // reconciliation has evidence about the uncertain attempt.
                if (effect.attempts() <= 0
                        || effect.workerId() == null
                        || effect.claimToken() == null
                        || effect.leaseUntilMillis() <= 0) {
                    throw new IllegalStateException(
                            "ambiguous persisted effect lacks claim evidence");
                }
                validateClaimToken(
                        effect,
                        runId,
                        nextClaimNumber);
            }
        }
        if (effect.state() == EffectState.COMPLETED) {
            if (effect.attempts() <= 0
                    || effect.receiptRef() == null) {
                throw new IllegalStateException(
                        "completed persisted effect lacks attempt/receipt");
            }
        } else if (effect.receiptRef() != null) {
            throw new IllegalStateException(
                    "non-completed persisted effect cannot carry receipt");
        }
    }

    private static long validateClaimToken(
            EffectSnapshot effect,
            String runId,
            long nextClaimNumber) {
        String[] parts = effect.claimToken().split("/", -1);
        if (parts.length != 9
                || !"workflow".equals(parts[0])
                || !"claim".equals(parts[1])
                || !"attempt".equals(parts[3])
                || !"nonce".equals(parts[5])
                || !"effect".equals(parts[7])) {
            throw new IllegalStateException(
                    "persisted claim token has invalid structure");
        }

        final long claimNumber;
        final int attempt;
        try {
            claimNumber = Long.parseLong(parts[2]);
            attempt = Integer.parseInt(parts[4]);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException(
                    "persisted claim token has invalid numeric fields",
                    failure);
        }
        String nonce = parts[6];
        if (!nonce.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "persisted claim token has invalid nonce");
        }
        if (claimNumber <= 0
                || claimNumber >= nextClaimNumber
                || attempt != effect.attempts()) {
            throw new IllegalStateException(
                    "persisted claim token sequence/attempt mismatch");
        }

        String expectedDigest = claimDigest(
                runId,
                effect.effectKey(),
                claimNumber,
                attempt,
                nonce);
        if (!MessageDigest.isEqual(
                expectedDigest.getBytes(StandardCharsets.US_ASCII),
                parts[8].getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException(
                    "persisted claim token effect digest mismatch");
        }
        return claimNumber;
    }

    private static long claimNumberFromToken(String claimToken) {
        String[] parts = claimToken.split("/", -1);
        if (parts.length != 9) {
            throw new IllegalStateException(
                    "persisted claim token has invalid structure");
        }
        try {
            return Long.parseLong(parts[2]);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException(
                    "persisted claim token has invalid claim number",
                    failure);
        }
    }

    private static String claimToken(
            String runId,
            String effectKey,
            int attempt,
            long claimNumber) {
        byte[] nonceBytes = new byte[CLAIM_NONCE_BYTES];
        CLAIM_RANDOM.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        return "workflow/claim/"
                + claimNumber
                + "/attempt/"
                + attempt
                + "/nonce/"
                + nonce
                + "/effect/"
                + claimDigest(
                        runId,
                        effectKey,
                        claimNumber,
                        attempt,
                        nonce);
    }

    private static String claimDigest(
            String runId,
            String effectKey,
            long claimNumber,
            int attempt,
            String nonce) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            digest.update(runId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(effectKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(claimNumber)
                    .getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(Integer.toString(attempt)
                    .getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(nonce.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    @Override
    public synchronized WorkflowJournalStoreV1.ReadView readView() {
        if (runId == null || maxIterations == null) {
            throw new IllegalStateException(
                    "cannot read a bound workflow view from an empty journal");
        }
        DurableDevLoopV1.ReplayResult replay = replayDevLoop();
        return new WorkflowJournalStoreV1.ReadView(
                snapshot(),
                replay,
                List.copyOf(journal),
                effects());
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


    private void replaceWith(WorkflowJournalV1 source) {
        this.runId = source.runId;
        this.maxIterations = source.maxIterations;
        this.nextSequence = source.nextSequence;
        this.nextClaimNumber = source.nextClaimNumber;
        this.lastObservedMillis = source.lastObservedMillis;

        this.journal.clear();
        this.journal.addAll(source.journal);

        this.effects.clear();
        for (Map.Entry<String, MutableEffect> entry
                : source.effects.entrySet()) {
            MutableEffect original = entry.getValue();
            MutableEffect copy = new MutableEffect(
                    original.effectKey,
                    original.command);
            copy.state = original.state;
            copy.attempts = original.attempts;
            copy.workerId = original.workerId;
            copy.claimToken = original.claimToken;
            copy.leaseUntilMillis = original.leaseUntilMillis;
            copy.receiptRef = original.receiptRef;
            copy.reconciliationRef = original.reconciliationRef;
            copy.retryAllowed = original.retryAllowed;
            this.effects.put(entry.getKey(), copy);
        }
    }

    private DurableDevLoopV1.Transition findAppliedTransition(
            String eventId,
            DurableDevLoopV1.Event event) {
        if (runId == null || maxIterations == null) {
            return null;
        }
        DurableDevLoopV1 machine =
                new DurableDevLoopV1(runId, maxIterations);
        DurableDevLoopV1.Transition applied = null;
        for (DurableDevLoopV1.JournalEntry recorded : journal) {
            DurableDevLoopV1.Transition transition =
                    machine.accept(
                            recorded.eventId(),
                            recorded.event());
            if (!transition.journalEntry().equals(recorded)) {
                throw new IllegalStateException(
                        "journal failed replay while locating outcome");
            }
            if (recorded.eventId().equals(eventId)
                    && recorded.event().equals(event)
                    && recorded.disposition()
                            == DurableDevLoopV1.Disposition.APPLIED) {
                applied = transition;
            }
        }
        return applied;
    }

    private void requireMatchingCausalEffect(
            String effectKey,
            DurableDevLoopV1.Transition transition) {
        String causal = causalEffectKey(
                transition.journalEntry().event(),
                transition.snapshot().runId(),
                transition.snapshot().iteration());
        if (causal == null || !causal.equals(effectKey)) {
            throw new IllegalStateException(
                    "effect/outcome causation mismatch: expected "
                            + effectKey
                            + " but workflow event resolves to "
                            + causal);
        }
    }

    private void verifyReducerTransitionLocked(
            DurableDevLoopV1.Transition supplied,
            String incomingRunId,
            int incomingMaxIterations) {
        DurableDevLoopV1 machine;
        if (journal.isEmpty()) {
            machine = new DurableDevLoopV1(
                    incomingRunId, incomingMaxIterations);
        } else {
            machine = DurableDevLoopV1.replay(
                    incomingRunId,
                    incomingMaxIterations,
                    List.copyOf(journal)).machine();
        }

        var entry = supplied.journalEntry();
        var expected = machine.accept(
                entry.eventId(), entry.event());

        if (!expected.journalEntry().equals(entry)) {
            throw new IllegalStateException(
                    "transition journal entry does not match reducer replay");
        }
        if (!expected.snapshot().equals(supplied.snapshot())) {
            throw new IllegalStateException(
                    "transition snapshot does not match reducer replay");
        }
        if (!expected.commands().equals(supplied.commands())) {
            throw new IllegalStateException(
                    "transition commands do not match reducer replay");
        }
    }

    private void prevalidateCausalEffectLocked(
            DurableDevLoopV1.JournalEntry entry,
            String incomingRunId,
            int currentIteration) {
        if (entry.disposition() != DurableDevLoopV1.Disposition.APPLIED) {
            return;
        }
        String key = causalEffectKey(
                entry.event(), incomingRunId, currentIteration);
        if (key == null) {
            return;
        }
        MutableEffect effect = effects.get(key);
        if (effect == null) {
            throw new IllegalStateException(
                    "applied workflow event lacks causal effect " + key);
        }
        if (!causalCommandMatches(entry.event(), effect.command)) {
            throw new IllegalStateException(
                    "causal effect command type mismatch for " + key);
        }
    }

    private void retireCausalEffectLocked(
            DurableDevLoopV1.Event event,
            String incomingRunId,
            int currentIteration) {
        String key = causalEffectKey(
                event, incomingRunId, currentIteration);
        if (key == null) {
            return;
        }
        MutableEffect effect = effects.get(key);
        if (effect.state == EffectState.PENDING) {
            effect.state = EffectState.ABANDONED;
        }
        // Once the causal outcome is accepted, no lifecycle state may regain
        // authority to execute this effect again. This bit is journal-derived.
        effect.retryAllowed = false;
    }

    private static boolean causalCommandMatches(
            DurableDevLoopV1.Event event,
            DurableDevLoopV1.Command command) {
        if (event instanceof DurableDevLoopV1.CodeChanged) {
            return command instanceof DurableDevLoopV1.RequestCode;
        }
        if (event instanceof DurableDevLoopV1.PushRecorded) {
            return command instanceof DurableDevLoopV1.PushCode;
        }
        if (event instanceof DurableDevLoopV1.CiCompleted) {
            return command instanceof DurableDevLoopV1.AwaitCi;
        }
        if (event instanceof DurableDevLoopV1.EvaluationPassed
                || event instanceof DurableDevLoopV1.EvaluationFailed) {
            return command instanceof DurableDevLoopV1.Evaluate;
        }
        if (event instanceof DurableDevLoopV1.BugReportCreated) {
            return command instanceof DurableDevLoopV1.CreateBugReport;
        }
        if (event instanceof DurableDevLoopV1.ApprovalGranted) {
            return command instanceof DurableDevLoopV1.RequestApproval;
        }
        return true;
    }

    private static String causalEffectKey(
            DurableDevLoopV1.Event event,
            String runId,
            int currentIteration) {
        String prefix = "devloop/" + runId + "/iteration/";
        if (event instanceof DurableDevLoopV1.CodeChanged changed) {
            return prefix + changed.iteration() + "/code";
        }
        if (event instanceof DurableDevLoopV1.PushRecorded pushed) {
            return prefix + pushed.iteration() + "/push";
        }
        if (event instanceof DurableDevLoopV1.CiCompleted ci) {
            return prefix + currentIteration + "/ci/" + ci.commitSha();
        }
        if (event instanceof DurableDevLoopV1.EvaluationPassed passed) {
            return prefix + currentIteration + "/evaluate/" + passed.commitSha();
        }
        if (event instanceof DurableDevLoopV1.EvaluationFailed failed) {
            return prefix + currentIteration + "/evaluate/" + failed.commitSha();
        }
        if (event instanceof DurableDevLoopV1.BugReportCreated bug) {
            return prefix + bug.iteration() + "/bug/" + bug.commitSha();
        }
        if (event instanceof DurableDevLoopV1.ApprovalGranted approval) {
            return prefix + currentIteration + "/approval/" + approval.commitSha();
        }
        return null;
    }

    private void abandonPendingEffectsLocked() {
        for (MutableEffect effect : effects.values()) {
            if (effect.state == EffectState.PENDING) {
                effect.state = EffectState.ABANDONED;
            }
            // Terminal workflow state revokes all pre-existing retry authority,
            // including already-completed effects. The newly emitted terminal
            // effect is inserted after this pass and retains its own authority.
            effect.retryAllowed = false;
        }
    }

    private static boolean isTerminal(DurableDevLoopV1.Phase phase) {
        return phase == DurableDevLoopV1.Phase.COMPLETED
                || phase == DurableDevLoopV1.Phase.FAILED
                || phase == DurableDevLoopV1.Phase.CANCELLED;
    }

    private MutableEffect requireLiveClaim(
            String effectKey,
            String claimToken,
            long nowMillis) {
        requireClock(nowMillis);
        MutableEffect effect =
                requireClaimed(effectKey, claimToken);
        observeClockLocked(nowMillis);
        if (effect.leaseUntilMillis <= nowMillis) {
            effect.state = EffectState.AMBIGUOUS;
            throw new IllegalStateException(
                    "effect claim lease expired; reconciliation required");
        }
        return effect;
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

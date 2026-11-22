package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A hot-upgrade plan for one actor incarnation (spec chapter 27, section 3).
 *
 * <p>Upgrading keeps the incarnation: its ActorId, ActorRefs, supervisor and
 * group memberships. It replaces the actor's code generation {@code N} with
 * {@code N+1}:</p>
 * <ol>
 *   <li><b>Freeze.</b> Admission of new user mail stops; mail keeps queueing.</li>
 *   <li><b>Drain.</b> Every generation-N invocation finishes on generation N:
 *       queued continuations, owned pending Futures, source tasks and a
 *       suspended receive. Nothing is migrated off a running stack. A
 *       continuation target captured under generation N is refused afterwards,
 *       so old code never runs against the new state.</li>
 *   <li><b>Switch.</b> On the actor's own turn, with nothing of generation N in
 *       flight: either {@link #sameSchema rebind} (the heap is kept, offsets
 *       stay valid) or {@link #migrating migrate}, which reads the old heap and
 *       writes a fresh one. Behavior, heap and code generation are installed
 *       together; the old heap is then scrubbed and retired.</li>
 *   <li><b>Queued mail.</b> Mail queued before the switch reaches generation
 *       N+1 only if {@link #admitsQueued} accepts it. Rejected mail is handed
 *       to {@link #onRejected} (with its sender) and counted, never delivered
 *       against the wrong type and never silently dropped.</li>
 * </ol>
 *
 * <p>Rollback: if the drain misses its deadline, or the rebind/migration
 * throws, generation N and its unchanged heap stay installed, a fresh heap is
 * scrubbed and retired, and admission resumes. Waiting never blocks a carrier.</p>
 */
public final class ActorUpgrade<M> {

    /** What a missed drain deadline does (spec ch. 27 rule 12). */
    public enum DeadlinePolicy {
        /** Abandon the upgrade: generation N stays installed and admission resumes. */
        ROLL_BACK,
        /**
         * Cancel generation N's outstanding owned Futures. Their continuations
         * still run on N and observe the cancellation, so cleanup runs; then the
         * switch proceeds. If N still has not drained after a second deadline,
         * the upgrade rolls back.
         */
        CANCEL
    }

    public enum Outcome {
        /** Generation N+1 is installed. */
        COMMITTED,
        /** Generation N did not drain before the deadline; N stays installed. */
        ROLLED_BACK_DRAIN_TIMEOUT,
        /** The rebind or migration failed; N and its heap stay installed. */
        ROLLED_BACK_MIGRATION_FAILED,
        /** The incarnation terminated before the upgrade finished. */
        ABORTED_ACTOR_TERMINATED
    }

    /**
     * @param codeGeneration the generation installed when the upgrade settled
     * @param rejectedMail   queued mail refused by the new generation
     * @param heapMigrated   whether a fresh heap replaced the old one
     * @param failureType    the failure's class name for a migration rollback, else empty
     */
    public record Result(Outcome outcome, long codeGeneration, long rejectedMail,
                         boolean heapMigrated, String failureType) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(failureType, "failureType");
        }
    }

    /** Same state schema: build generation N+1 over the kept heap. */
    @FunctionalInterface
    public interface Rebind<M> {
        ActorRuntime.Behavior<M> rebind(ActorRuntime.Behavior<M> previous,
                                        ActorRuntime.ActorContext<M> context) throws Exception;
    }

    /**
     * Changed state schema: read {@code oldHeap}, write {@code newHeap}, and
     * return generation N+1. Must be total and bounded, and must not perform
     * I/O or retain {@code oldHeap}, which is retired after the switch.
     */
    @FunctionalInterface
    public interface Migrate<M> {
        ActorRuntime.Behavior<M> migrate(ActorRuntime.Behavior<M> previous,
                                         ActorNativeArena oldHeap,
                                         ActorNativeArena newHeap,
                                         ActorRuntime.ActorContext<M> context) throws Exception;
    }

    static final Duration DEFAULT_DRAIN_DEADLINE = Duration.ofSeconds(5);

    final Rebind<M> rebind;
    final Migrate<M> migrate;
    final Predicate<? super M> admitsQueued;
    final Consumer<ActorRuntime.ActorInboxMail<M>> onRejected;
    final Duration drainDeadline;
    final DeadlinePolicy deadlinePolicy;

    private ActorUpgrade(Rebind<M> rebind, Migrate<M> migrate, Predicate<? super M> admitsQueued,
                         Consumer<ActorRuntime.ActorInboxMail<M>> onRejected, Duration drainDeadline,
                         DeadlinePolicy deadlinePolicy) {
        this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy");
        this.rebind = rebind;
        this.migrate = migrate;
        this.admitsQueued = Objects.requireNonNull(admitsQueued, "admitsQueued");
        this.onRejected = Objects.requireNonNull(onRejected, "onRejected");
        this.drainDeadline = Objects.requireNonNull(drainDeadline, "drainDeadline");
        if (drainDeadline.isNegative() || drainDeadline.isZero()) {
            throw new IllegalArgumentException("drain deadline must be positive");
        }
    }

    public static <M> ActorUpgrade<M> sameSchema(Rebind<M> rebind) {
        return new ActorUpgrade<>(Objects.requireNonNull(rebind, "rebind"), null,
                message -> true, mail -> { }, DEFAULT_DRAIN_DEADLINE, DeadlinePolicy.ROLL_BACK);
    }

    public static <M> ActorUpgrade<M> migrating(Migrate<M> migrate) {
        return new ActorUpgrade<>(null, Objects.requireNonNull(migrate, "migrate"),
                message -> true, mail -> { }, DEFAULT_DRAIN_DEADLINE, DeadlinePolicy.ROLL_BACK);
    }

    /** Which mail queued under generation N generation N+1 still accepts. */
    public ActorUpgrade<M> admitsQueued(Predicate<? super M> admits) {
        return new ActorUpgrade<>(rebind, migrate, admits, onRejected, drainDeadline, deadlinePolicy);
    }

    /** Receives each rejected queued message, with its sender, on the actor's turn. */
    public ActorUpgrade<M> onRejected(Consumer<ActorRuntime.ActorInboxMail<M>> sink) {
        return new ActorUpgrade<>(rebind, migrate, admitsQueued, sink, drainDeadline, deadlinePolicy);
    }

    public ActorUpgrade<M> drainDeadline(Duration deadline) {
        return new ActorUpgrade<>(rebind, migrate, admitsQueued, onRejected, deadline, deadlinePolicy);
    }

    public ActorUpgrade<M> onDrainDeadline(DeadlinePolicy policy) {
        return new ActorUpgrade<>(rebind, migrate, admitsQueued, onRejected, drainDeadline, policy);
    }

    /**
     * Deadline timers and off-lock outcome publication. The thread only flips
     * upgrade state under the actor's lifecycle lock or settles a Future; it
     * never runs guest or behavior code.
     */
    static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ores-actor-upgrade-deadline");
        thread.setDaemon(true);
        return thread;
    });
}

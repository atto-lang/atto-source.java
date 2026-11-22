package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supervisor-only config reload transaction for one persistent NativeHttp listener.
 *
 * <p>VALIDATE -> STAGE -> WARM -> PUBLISH is reversible until publish.
 * After publish the old generation must DRAIN and await authenticated physical
 * retirement (not yet implemented); there is no automatic rollback of new
 * requests across an already published epoch. No guest actor may invoke this
 * authority, and configuration snapshots are deeply immutable strings.</p>
 */
public final class NativeHttpRollingReloadCoordinator implements AutoCloseable {
    public record Config(String service, long revision, Map<String, String> settings) {
        public Config {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(settings, "settings");
            if (!service.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}") || revision < 1)
                throw new IllegalArgumentException("invalid service name or revision");
            if (settings.size() > 128)
                throw new IllegalArgumentException("too many configuration entries");
            Map<String, String> copy = new LinkedHashMap<>();
            for (var entry : settings.entrySet()) {
                String key = Objects.requireNonNull(entry.getKey(), "config key");
                String value = Objects.requireNonNull(entry.getValue(), "config value");
                if (!key.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                        || value.length() > 4096 || value.indexOf('\0') >= 0)
                    throw new IllegalArgumentException("invalid config entry");
                copy.put(key, value);
            }
            settings = Map.copyOf(copy);
        }
    }

    public record Published(long epoch, Config config) { }
    public enum Status { PUBLISHED, STALE }
    public record Result(Status status, long epoch, Config config) { }

    /** Must validate full configuration, ABI, capabilities and listener invariants. */
    @FunctionalInterface
    public interface Validator {
        void validate(Config previous, Config proposed) throws Exception;
    }

    /** Creates an unpooled or pooled supervisor-owned actor handler for staging. */
    @FunctionalInterface
    public interface Factory {
        NativeHttp.Handler create(Config proposed) throws Exception;
    }

    private final NativeHttpRollingDispatcher dispatcher;
    private final Validator validator;
    private final Factory factory;
    private final Semaphore stagingCapacity = new Semaphore(8);
    private final ExecutorService transactions = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("ores-http-config-transactions", 0).factory());
    private final AtomicReference<Published> published;
    private final AtomicBoolean closed = new AtomicBoolean();

    public NativeHttpRollingReloadCoordinator(NativeHttpRollingDispatcher dispatcher,
                                               Config initial,
                                               Validator validator,
                                               Factory factory) {
        requireSupervisor();
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.factory = Objects.requireNonNull(factory, "factory");
        Config config = Objects.requireNonNull(initial, "initial");
        this.published = new AtomicReference<>(
                new Published(dispatcher.snapshot().activeEpoch(), config));
    }

    public Published published() { return published.get(); }

    /**
     * A request captures an expected route epoch. Two concurrent candidates
     * may warm, but only one may publish that epoch; the loser is cancelled.
     * Validation failures have no effect on the serving listener or G1.
     */
    public OresFuture<Result> reload(long expectedEpoch, Config proposed) {
        requireSupervisor();
        Objects.requireNonNull(proposed, "proposed");
        if (closed.get()) throw new IllegalStateException("reload coordinator closed");
        if (!stagingCapacity.tryAcquire())
            throw new RejectedExecutionException("reload staging capacity reached");
        OresFuture<Result> outcome = new OresFuture<>();
        try {
            transactions.execute(() -> begin(expectedEpoch, proposed, outcome));
        } catch (RuntimeException | Error rejected) {
            stagingCapacity.release();
            outcome.failFromRuntime(rejected);
        }
        return outcome;
    }

    private void begin(long expectedEpoch, Config proposed, OresFuture<Result> outcome) {
        try {
            if (closed.get()) throw new IllegalStateException("reload coordinator closed");
            Published old = published.get();
            long routeEpoch = dispatcher.snapshot().activeEpoch();
            if (old.epoch() != expectedEpoch || routeEpoch != expectedEpoch) {
                resolve(outcome, new Result(Status.STALE, routeEpoch, old.config()));
                stagingCapacity.release();
                return;
            }
            if (!old.config().service().equals(proposed.service())
                    || proposed.revision() <= old.config().revision())
                throw new IllegalArgumentException("incompatible service identity or nonmonotonic revision");

            // Mandatory host-supplied compatibility validation runs BEFORE
            // executing the factory or spawning a candidate actor.
            validator.validate(old.config(), proposed);
            if (closed.get()) throw new IllegalStateException("reload coordinator closed");
            NativeHttp.Handler candidate = Objects.requireNonNull(
                    factory.create(proposed), "factory returned null candidate");
            // Candidate ownership and all worker ready receipts are checked
            // by the dispatcher, in addition to route epoch compare-and-swap.
            OresFuture<Boolean> stage;
            try {
                stage = dispatcher.promoteAfterWarmup(expectedEpoch, candidate);
            } catch (RuntimeException | Error rejected) {
                // Candidate was constructed but never admitted for warmup.
                // Do not cancel an active or concurrently staged pool.
                try { dispatcher.discardRejectedCandidate(candidate); }
                catch (RuntimeException | Error cleanupFailure) {
                    rejected.addSuppressed(cleanupFailure);
                }
                throw rejected;
            }
            stage.whenCompleteRuntime((success, failure) -> {
                // Publication is complete when this callback runs. Commit
                // metadata on that SAME callback turn, before reporting a
                // successful Future; queuing another executor turn can race
                // shutdown and strand metadata after a route CAS succeeds.
                try {
                    if (failure != null) {
                        outcome.failFromRuntime(OresFuture.unwrap(failure));
                    } else if (!Boolean.TRUE.equals(success)) {
                        Published current = published.get();
                        resolve(outcome, new Result(Status.STALE,
                                dispatcher.snapshot().activeEpoch(), current.config()));
                    } else {
                        Published installed = new Published(expectedEpoch + 1, proposed);
                        if (!published.compareAndSet(old, installed)) {
                            outcome.failFromRuntime(new IllegalStateException(
                                    "published generation metadata raced"));
                        } else {
                            resolve(outcome, new Result(
                                    Status.PUBLISHED, installed.epoch(), installed.config()));
                        }
                    }
                } catch (RuntimeException | Error unexpected) {
                    // Fail closed on any callback/plumbing bug. Never tell a
                    // caller it succeeded without publishing its metadata.
                    outcome.failFromRuntime(unexpected);
                } finally {
                    stagingCapacity.release();
                }
            });
        } catch (Exception | Error failure) {
            stagingCapacity.release();
            outcome.failFromRuntime(failure);
        }
    }

    private static void resolve(OresFuture<Result> outcome, Result value) {
        outcome.completeFromRuntime(value);
    }

    private static void requireSupervisor() {
        if (ActorRuntime.inActorExecution())
            throw new SecurityException("only the trusted supervisor may reload HTTP actors");
    }

    /**
     * Stops the owned service, not just coordinator bookkeeping, to prevent
     * an in-flight candidate from publishing after shutdown. This is explicit
     * shutdown, never part of a routine configuration reload.
     */
    @Override public void close() {
        requireSupervisor();
        if (!closed.compareAndSet(false, true)) return;
        dispatcher.close();
        transactions.shutdown();
    }
}

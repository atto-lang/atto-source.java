package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.AbstractExecutorService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes REAL ActorRuntime mailbox turns through a test-owned choice queue.
 * No sleeping, guest-visible instrumentation, synthetic actor message delivery,
 * or production dispatcher replacement is used.
 *
 * This is a Java HOST-runtime test, not a compiled .atto guest or AOT proof.
 */
final class DeterministicActorDispatchTest {
    private record Ticket(String id, ActorRuntime.ActorKind kind, Runnable runnable) { }
    private record Decision(int seq, List<String> enabled, String choice, List<String> observed) { }
    private record Trace(long seed, List<Decision> decisions, List<String> observed) { }

    /** Single-threaded test driver: execution occurs only after explicit selection. */
    private static final class ControlledDispatchers
            implements ActorRuntime.DispatcherFactoryForTesting {
        private final List<Ticket> pending = new ArrayList<>();
        private final Map<ActorRuntime.ActorKind, StepExecutor> pools =
                new EnumMap<>(ActorRuntime.ActorKind.class);
        private int sequence;
        private boolean executing;
        private final Thread owner = Thread.currentThread();

        private void requireDriverThread() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException(
                        "uncontrolled cross-thread actor scheduler event");
        }

        @Override
        public ExecutorService create(
                ActorRuntime.ActorKind kind, int parallelism, int capacity) {
            assertTrue(parallelism > 0 && capacity > 0);
            assertFalse(pools.containsKey(kind), "one controlled queue per isolation kind");
            var pool = new StepExecutor(kind, Math.min(64, capacity));
            pools.put(kind, pool);
            return pool;
        }

        private final class StepExecutor extends AbstractExecutorService {
            private final ActorRuntime.ActorKind kind;
            private final int capacity;
            private boolean stopped;
            StepExecutor(ActorRuntime.ActorKind kind, int capacity) {
                this.kind = kind;
                this.capacity = capacity;
            }
            @Override
            public void execute(Runnable command) {
                Objects.requireNonNull(command);
                requireDriverThread();
                if (stopped || pending.stream().filter(t -> t.kind() == kind).count() >= capacity)
                    throw new RejectedExecutionException("controlled actor queue closed/full");
                pending.add(new Ticket("turn-" + (++sequence), kind, command));
            }
            @Override
            public void shutdown() { stopped = true; }
            @Override
            public List<Runnable> shutdownNow() {
                stopped = true;
                List<Runnable> cancelled = pending.stream()
                        .filter(t -> t.kind() == kind).map(Ticket::runnable).toList();
                pending.removeIf(t -> t.kind() == kind);
                return cancelled;
            }
            @Override
            public boolean isShutdown() { return stopped; }
            @Override
            public boolean isTerminated() {
                return stopped && pending.stream().noneMatch(t -> t.kind() == kind);
            }
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return isTerminated();
            }
        }

        List<String> enabled() {
            requireDriverThread();
            return pending.stream().map(Ticket::id).sorted().toList();
        }

        void choose(String id) {
            requireDriverThread();
            assertFalse(executing, "controlled actor turns cannot nest");
            Ticket chosen = pending.stream().filter(t -> t.id().equals(id))
                    .findFirst().orElseThrow(() ->
                            new IllegalStateException("replay choice not enabled: " + id));
            pending.remove(chosen);
            executing = true;
            try {
                // The real ActorRuntime physical-turn lease and TurnExecutor
                // still govern this runnable; a queue entry cannot spoof them.
                chosen.runnable().run();
            } finally {
                executing = false;
            }
        }
    }

    private static Trace run(long seed, Trace replay) throws Exception {
        if (replay != null && replay.seed() != seed)
            throw new IllegalStateException("replay seed mismatch");
        ControlledDispatchers driver = new ControlledDispatchers();
        List<String> delivered = new ArrayList<>();
        var config = new ActorRuntime.DispatcherConfig(1, 1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                config, Runnable::run, ActorRuntime.ControlDispatcher.unavailable(),
                null, driver)) {
            var left = runtime.<Integer>spawnShared(() -> (message, context) -> {
                assertSame(runtime, ActorRuntime.currentActorRuntime());
                assertTrue(ActorRuntime.currentActorGeneration().isPresent());
                delivered.add("A:" + message);
                context.self().stop();
            });
            var right = runtime.<Integer>spawnShared(() -> (message, context) -> {
                assertSame(runtime, ActorRuntime.currentActorRuntime());
                assertTrue(ActorRuntime.currentActorGeneration().isPresent());
                delivered.add("B:" + message);
                context.self().stop();
            });

            left.send(1);
            right.send(2);
            SplittableRandom random = new SplittableRandom(seed);
            List<Decision> decisions = new ArrayList<>();
            for (int step = 0; step < 32 && !driver.enabled().isEmpty(); step++) {
                List<String> enabled = driver.enabled();
                Decision expected = replay == null ? null :
                        step < replay.decisions().size() ? replay.decisions().get(step) :
                                throwMismatch("replay missing decision");
                String choice = replay == null
                        ? enabled.get(random.nextInt(enabled.size())) : expected.choice();
                if (expected != null && (expected.seq() != step
                        || !expected.enabled().equals(enabled))) {
                    throw new IllegalStateException("replay runnable-set mismatch");
                }
                if (!enabled.contains(choice))
                    throw new IllegalStateException("replay choice not enabled: " + choice);
                driver.choose(choice);
                Decision actual = new Decision(step, List.copyOf(enabled),
                        choice, List.copyOf(delivered));
                if (expected != null && !expected.equals(actual))
                    throw new IllegalStateException("replay observable divergence");
                decisions.add(actual);
            }
            if (!driver.enabled().isEmpty())
                throw new IllegalStateException("controlled actor turn budget exceeded");
            if (!left.awaitTermination(1, TimeUnit.SECONDS)
                    || !right.awaitTermination(1, TimeUnit.SECONDS))
                throw new IllegalStateException("actor did not reach physical termination");
            if (delivered.size() != 2)
                throw new IllegalStateException("lost or duplicated actor delivery");
            Trace result = new Trace(seed, List.copyOf(decisions), List.copyOf(delivered));
            if (replay != null && (!replay.equals(result)))
                throw new IllegalStateException("replay terminal mismatch");
            return result;
        }
    }

    // Allows an exhaustive replay length check without a synthetic decision.
    private static Decision throwMismatch(String reason) {
        throw new IllegalStateException(reason);
    }

    @Test
    void sameSeedReplaysActualMailboxTurnsExactly() throws Exception {
        for (int seed = 0; seed < 32; seed++) {
            Trace first = run(seed, null);
            assertEquals(first, run(seed, null));
            assertEquals(first, run(seed, first));
            assertTrue(first.decisions().size() >= 2,
                    "both actor mailboxes require genuine physical dispatch");
            assertTrue(first.decisions().size() <= 32, "bounded execution");
        }
    }

    @Test
    void exploreBothLegalActorOrdersWithoutSleepingOrReentrantDispatch() throws Exception {
        boolean leftFirst = false;
        boolean rightFirst = false;
        for (int seed = 0; seed < 64; seed++) {
            Trace trace = run(seed, null);
            if (trace.observed().equals(List.of("A:1", "B:2"))) leftFirst = true;
            else if (trace.observed().equals(List.of("B:2", "A:1"))) rightFirst = true;
            else fail("unexpected actor order " + trace.observed());
        }
        assertTrue(leftFirst && rightFirst,
                "the runtime must execute both legal mailbox turn schedules");
    }

    @Test
    void tamperedReplayFailsClosedBeforeExecutingAnImpossibleChoice() throws Exception {
        Trace authentic = run(17, null);
        var decisions = new ArrayList<>(authentic.decisions());
        Decision first = decisions.get(0);
        decisions.set(0, new Decision(0, List.of("turn-999"),
                first.choice(), first.observed()));
        Trace forged = new Trace(authentic.seed(), decisions, authentic.observed());
        assertThrows(IllegalStateException.class, () -> run(17, forged));

        decisions.set(0, new Decision(0, first.enabled(), "turn-999", first.observed()));
        assertThrows(IllegalStateException.class,
                () -> run(17, new Trace(authentic.seed(), decisions, authentic.observed())));
    }

    @Test
    void perActorMailboxPreservesFifoAcrossControlledPhysicalTurns() throws Exception {
        ControlledDispatchers driver = new ControlledDispatchers();
        List<Integer> observed = new ArrayList<>();
        var config = new ActorRuntime.DispatcherConfig(1, 1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                config, Runnable::run, ActorRuntime.ControlDispatcher.unavailable(),
                null, driver)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                observed.add(message);
                if (message == 2) context.self().stop();
            });
            ref.send(1);
            ref.send(2);
            int turns = 0;
            while (!driver.enabled().isEmpty()) {
                if (++turns > 32)
                    throw new IllegalStateException("FIFO mailbox test exhausted turn budget");
                driver.choose(driver.enabled().get(0));
            }
            assertEquals(List.of(1, 2), observed,
                    "deterministic dispatch controls turns but must not reorder a FIFO mailbox");
            assertTrue(ref.awaitTermination(1, TimeUnit.SECONDS));
        }
    }
}

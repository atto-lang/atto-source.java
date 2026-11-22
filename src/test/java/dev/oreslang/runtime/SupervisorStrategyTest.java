package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.runtime.SupervisorStrategy.ChildSpec;
import dev.oreslang.runtime.SupervisorStrategy.Children;
import dev.oreslang.runtime.SupervisorStrategy.Mode;
import dev.oreslang.runtime.SupervisorStrategy.Restart;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Restart strategies on the supervisor CONTROL lane: which children a failure
 * replaces, that every replacement is a fresh incarnation (new id, new heap,
 * old refs fenced), restart types, bounded intensity with escalation through
 * a supervision tree, and a seeded model of arbitrary failure sequences.
 */
@Timeout(60)
final class SupervisorStrategyTest {

    /**
     * Child factories must be stateless (no captured host state), so workers
     * report through static, test-owned state: each incarnation records its
     * private heap under its own ActorId on its first turn.
     */
    private static final Map<ActorRuntime.ActorId, ActorNativeArena> HEAPS = new ConcurrentHashMap<>();

    @BeforeEach
    void clearHeaps() {
        HEAPS.clear();
    }

    /** One stateless factory for every worker: "fail" throws, "quit" cancels itself. */
    private static ActorRuntime.Behavior<String> workerBehavior(ActorRuntime.ActorContext<String> context) {
        return (message, turn) -> {
            HEAPS.putIfAbsent(turn.self().id(), turn.heap());
            switch (message) {
                case "fail" -> throw new IllegalStateException("injected worker failure");
                case "quit" -> turn.self().cancel();
                default -> { }
            }
        };
    }

    private static ChildSpec<String> worker(String name, Restart restart) {
        return new ChildSpec<>(name, ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(), restart,
                SupervisorStrategyTest::workerBehavior);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    @SuppressWarnings("unchecked")
    private static ActorRuntime.ActorRef<String> ref(Children children, String name) {
        return (ActorRuntime.ActorRef<String>) children.current(name).orElseThrow(
                () -> new AssertionError("child " + name + " is not running"));
    }

    /** Pings every running child so each records its incarnation. */
    private static void touch(Children children, String... names) throws InterruptedException {
        for (String name : names) {
            ActorRuntime.ActorRef<String> ref = ref(children, name);
            ref.send("ping");
        }
    }

    private static ActorRuntime.ActorRef<String> spawnSupervisor(
            ActorRuntime runtime, Mode mode, int maxRestarts, Duration window,
            List<ChildSpec<?>> specs, LongSupplierClock clock, Children children) {
        ActorRuntime.ActorRef<String> supervisor = runtime.spawnShared(SupervisorStrategy.<String>supervisor(
                mode, maxRestarts, window, specs, clock::now, children));
        supervisor.ready(); // actors are constructed on their first turn
        return supervisor;
    }

    /** A clock tests can move forward by hand. */
    private static final class LongSupplierClock {
        private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        long now() { return nanos.get(); }
        void advance(Duration by) { nanos.addAndGet(by.toNanos()); }
    }

    private static List<ChildSpec<?>> abc(Restart restart) {
        return List.of(worker("a", restart), worker("b", restart), worker("c", restart));
    }

    private void checkStrategy(Mode mode, Map<String, Long> expectedStarts) throws Exception {
        Children children = new Children();
        try (ActorRuntime runtime = new ActorRuntime()) {
            spawnSupervisor(runtime, mode, 10, Duration.ofMinutes(1),
                    abc(Restart.PERMANENT), new LongSupplierClock(), children);
            await(() -> children.starts("c") == 1, "children started");
            Map<String, ActorRuntime.ActorRef<?>> before = Map.of(
                    "a", ref(children, "a"), "b", ref(children, "b"), "c", ref(children, "c"));
            touch(children, "a", "b", "c");
            await(() -> HEAPS.size() == 3, "first incarnations recorded");

            ref(children, "b").send("fail");
            await(() -> children.starts("b") == 2, "b restarted");
            await(() -> expectedStarts.entrySet().stream()
                    .allMatch(e -> children.starts(e.getKey()) == e.getValue()), "strategy restarts");
            Thread.sleep(100); // nothing further may restart once the strategy settles
            for (var expected : expectedStarts.entrySet()) {
                assertEquals(expected.getValue(), children.starts(expected.getKey()),
                        mode + ": starts of " + expected.getKey());
                if (expected.getValue() == 1) {
                    assertEquals(before.get(expected.getKey()), ref(children, expected.getKey()),
                            mode + ": " + expected.getKey() + " must keep its incarnation");
                } else {
                    assertNotEquals(before.get(expected.getKey()).id(), ref(children, expected.getKey()).id(),
                            mode + ": " + expected.getKey() + " must be a new incarnation");
                    assertTrue(before.get(expected.getKey()).awaitTermination(5, TimeUnit.SECONDS),
                            mode + ": the replaced incarnation of " + expected.getKey() + " must terminate");
                }
            }
            assertEquals(1, children.restartEvents(), mode + ": one failure is one restart event");

            // A replaced incarnation is fenced; the new one serves and owns a new heap.
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<String> oldB = (ActorRuntime.ActorRef<String>) before.get("b");
            assertTrue(oldB.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> oldB.send("ping"), "old incarnation is fenced");
            ActorRuntime.ActorRef<String> newB = ref(children, "b");
            newB.send("ping");
            await(() -> HEAPS.containsKey(newB.id()), "new b incarnation served");
            assertNotSame(HEAPS.get(oldB.id()), HEAPS.get(newB.id()), "a restart gets a fresh heap");
        }
    }

    @Test
    void oneForOneReplacesOnlyTheFailedChild() throws Exception {
        checkStrategy(Mode.ONE_FOR_ONE, Map.of("a", 1L, "b", 2L, "c", 1L));
    }

    @Test
    void oneForAllReplacesEveryChildOnce() throws Exception {
        checkStrategy(Mode.ONE_FOR_ALL, Map.of("a", 2L, "b", 2L, "c", 2L));
    }

    @Test
    void restForOneReplacesTheFailedChildAndThoseDeclaredAfterIt() throws Exception {
        checkStrategy(Mode.REST_FOR_ONE, Map.of("a", 1L, "b", 2L, "c", 2L));
    }

    @Test
    void restartTypesDecideWhetherATerminatedChildIsReplaced() throws Exception {
        Children children = new Children();
        try (ActorRuntime runtime = new ActorRuntime()) {
            spawnSupervisor(runtime, Mode.ONE_FOR_ONE, 10, Duration.ofMinutes(1), List.of(
                    worker("permanent", Restart.PERMANENT),
                    worker("transient", Restart.TRANSIENT),
                    worker("temporary", Restart.TEMPORARY)),
                    new LongSupplierClock(), children);
            await(() -> children.starts("temporary") == 1, "children started");

            ref(children, "transient").send("fail");
            await(() -> children.starts("transient") == 2, "transient restarted after a failure");
            ActorRuntime.ActorRef<String> transientRef = ref(children, "transient");
            transientRef.send("quit");
            await(() -> children.current("transient").isEmpty(), "transient cancellation processed");

            ref(children, "permanent").send("quit");
            await(() -> children.starts("permanent") == 2, "permanent restarted after cancellation");

            ref(children, "temporary").send("fail");
            await(() -> children.current("temporary").isEmpty(), "temporary failure processed");
            Thread.sleep(100);
            assertEquals(2, children.starts("transient"), "transient is not replaced after cancellation");
            assertEquals(1, children.starts("temporary"), "temporary is never replaced");
            assertEquals(2, children.starts("permanent"));
        }
    }

    @Test
    void exceedingTheRestartIntensityFailsTheSupervisorAndEscalatesToItsParent() throws Exception {
        Children inner = new Children();
        Children outer = new Children();
        LongSupplierClock clock = new LongSupplierClock();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ChildSpec<String> innerSupervisor = new ChildSpec<>("inner", ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(), Restart.PERMANENT,
                    SupervisorStrategy.<String>supervisor(Mode.ONE_FOR_ONE, 2, Duration.ofMinutes(1),
                            List.of(worker("w", Restart.PERMANENT)), clock::now, inner));
            ActorRuntime.ActorRef<String> top = spawnSupervisor(runtime, Mode.ONE_FOR_ONE, 5,
                    Duration.ofMinutes(1), List.of(innerSupervisor), clock, outer);
            await(() -> inner.starts("w") == 1, "tree started");
            ActorRuntime.ActorRef<?> firstInner = outer.current("inner").orElseThrow();

            // Two restarts fit the inner budget; the third failure exceeds it.
            ref(inner, "w").send("fail");
            await(() -> inner.starts("w") == 2, "restart 1");
            ref(inner, "w").send("fail");
            await(() -> inner.starts("w") == 3, "restart 2");
            ref(inner, "w").send("fail");

            // The inner supervisor fails; the outer supervisor replaces it, and
            // the fresh inner incarnation starts a fresh worker with a new budget.
            await(() -> outer.starts("inner") == 2, "outer restarted the failed inner supervisor");
            assertTrue(firstInner.awaitTermination(5, TimeUnit.SECONDS));
            Throwable raw = firstInner.failure().orElseThrow();
            assertTrue(raw instanceof SupervisorStrategy.RestartIntensityExceededException,
                    "inner failed on its restart budget: " + raw);
            await(() -> inner.starts("w") == 4, "fresh inner started a fresh worker");
            assertEquals(2, inner.restartEvents(), "the exceeding failure was not a restart");
            assertEquals(1, outer.restartEvents());
            assertTrue(top.isAlive(), "the outer supervisor absorbed the escalation");
        }
    }

    @Test
    void theRestartBudgetIsARollingWindow() throws Exception {
        Children children = new Children();
        LongSupplierClock clock = new LongSupplierClock();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> supervisor = spawnSupervisor(runtime, Mode.ONE_FOR_ONE, 2,
                    Duration.ofSeconds(10), List.of(worker("w", Restart.PERMANENT)), clock, children);
            await(() -> children.starts("w") == 1, "started");
            for (int round = 0; round < 3; round++) {
                ref(children, "w").send("fail");
                long expected = 2L + 2L * round;
                await(() -> children.starts("w") == expected, "first restart of window " + round);
                ref(children, "w").send("fail");
                await(() -> children.starts("w") == expected + 1, "second restart of window " + round);
                clock.advance(Duration.ofSeconds(11)); // the window slides past both
            }
            assertTrue(supervisor.isAlive(), "spread-out failures never exhaust the budget");
            assertEquals(6, children.restartEvents());
        }
    }

    @Test
    void aSupervisorWithoutAParentSupervisorFailsClosedOnExhaustion() throws Exception {
        Children children = new Children();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> supervisor = spawnSupervisor(runtime, Mode.ONE_FOR_ONE, 1,
                    Duration.ofMinutes(1), List.of(worker("w", Restart.PERMANENT)),
                    new LongSupplierClock(), children);
            await(() -> children.starts("w") == 1, "started");
            ref(children, "w").send("fail");
            await(() -> children.starts("w") == 2, "restart 1");
            ref(children, "w").send("fail");
            assertTrue(supervisor.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(supervisor.isAlive());
            ExecutionException observed = assertThrows(ExecutionException.class,
                    () -> supervisor.done().get(5, TimeUnit.SECONDS));
            assertTrue(observed.getCause() instanceof ActorRuntime.ActorFailureSnapshotException,
                    "observers see the cause-free snapshot: " + observed.getCause());
            assertTrue(supervisor.failure().orElseThrow()
                    instanceof SupervisorStrategy.RestartIntensityExceededException);
            assertEquals(2, children.starts("w"), "no restart after exhaustion");
        }
    }

    @Test
    void specsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> SupervisorStrategy.supervisor(
                Mode.ONE_FOR_ONE, 1, Duration.ofSeconds(1),
                List.of(worker("x", Restart.PERMANENT), worker("x", Restart.PERMANENT)),
                new Children()));
        assertThrows(IllegalArgumentException.class, () -> SupervisorStrategy.supervisor(
                Mode.ONE_FOR_ONE, 1, Duration.ofSeconds(1), List.of(), new Children()));
        assertThrows(IllegalArgumentException.class, () -> SupervisorStrategy.supervisor(
                Mode.ONE_FOR_ONE, 0, Duration.ofSeconds(1), List.of(worker("x", Restart.PERMANENT)),
                new Children()));
    }

    /**
     * Seeded model: random modes, restart types and failure/cancel sequences.
     * After every event the observed start count of every child must equal
     * the model's, and no spurious restart may appear while the tree settles.
     */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 7, 42, 1337, 65537, 2026})
    void arbitraryFailureSequencesMatchTheModel(long seed) throws Exception {
        Random random = new Random(seed);
        Mode mode = Mode.values()[random.nextInt(Mode.values().length)];
        int n = 3 + random.nextInt(3);
        Restart[] types = new Restart[n];
        List<ChildSpec<?>> specs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            types[i] = random.nextInt(4) == 0 ? Restart.TEMPORARY
                    : random.nextBoolean() ? Restart.TRANSIENT : Restart.PERMANENT;
            specs.add(worker("c" + i, types[i]));
        }
        long[] starts = new long[n];
        boolean[] alive = new boolean[n];
        java.util.Arrays.fill(starts, 1);
        java.util.Arrays.fill(alive, true);
        Children children = new Children();
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> supervisor = spawnSupervisor(runtime, mode, 1_000,
                    Duration.ofMinutes(1), specs, new LongSupplierClock(), children);
            await(() -> children.starts("c" + (n - 1)) == 1, "seed " + seed + " started");
            long events = 0;
            for (int step = 0; step < 25; step++) {
                List<Integer> live = new ArrayList<>();
                for (int i = 0; i < n; i++) if (alive[i]) live.add(i);
                if (live.isEmpty()) break;
                int victim = live.get(random.nextInt(live.size()));
                boolean cancel = random.nextInt(3) == 0;
                String where = "seed " + seed + " " + mode + " step " + step + ": "
                        + (cancel ? "cancel" : "fail") + " c" + victim + " (" + types[victim] + ")";
                ActorRuntime.ActorRef<String> old = ref(children, "c" + victim);

                // Model.
                boolean replace = switch (types[victim]) {
                    case PERMANENT -> true;
                    case TRANSIENT -> !cancel;
                    case TEMPORARY -> false;
                };
                alive[victim] = false;
                if (replace) {
                    events++;
                    int from = mode == Mode.ONE_FOR_ONE ? victim : mode == Mode.REST_FOR_ONE ? victim : 0;
                    int to = mode == Mode.ONE_FOR_ONE ? victim : n - 1;
                    for (int i = from; i <= to; i++) {
                        if (i == victim) {
                            starts[i]++;
                            alive[i] = true;
                        } else if (alive[i]) {
                            if (types[i] == Restart.TEMPORARY) {
                                alive[i] = false; // stopped, not started again
                            } else {
                                starts[i]++;
                            }
                        }
                    }
                }

                old.send(cancel ? "quit" : "fail");
                await(() -> !children.current("c" + victim).map(r -> r.equals(old)).orElse(false),
                        where + " processed");
                long[] expected = starts.clone();
                await(() -> {
                    for (int i = 0; i < n; i++) if (children.starts("c" + i) != expected[i]) return false;
                    return true;
                }, where + " restarts");
                Thread.sleep(15);
                for (int i = 0; i < n; i++) {
                    assertEquals(starts[i], children.starts("c" + i), where + ": starts of c" + i);
                    assertEquals(alive[i], children.current("c" + i).isPresent(), where + ": c" + i + " running");
                }
            }
            assertEquals(events, children.restartEvents(), "seed " + seed + ": restart events");
            assertTrue(supervisor.isAlive(), "seed " + seed);
        }
    }
}

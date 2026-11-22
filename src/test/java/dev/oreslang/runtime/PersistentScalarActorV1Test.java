package dev.oreslang.runtime;

import dev.oreslang.compiler.PersistentScalarMirV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Parser -> protected MIR -> fenced SQLite state-turn integration. */
final class PersistentScalarActorV1Test {
    @TempDir Path temp;

    static final String SOURCE = """
            define persistent actor Counter as
              state {
                let int count: 0;
              }
              increment(int amount) -> int ::
                return self.handle_state(critical-write |s| -> {
                  s.count = s.count + amount;
                  s.count;
                });
              end
              get_count() -> int ::
                return self.handle_state(critical-read |s| -> s.count);
              end
            end
            """;

    private static final DurableActorStore.Address ID =
            new DurableActorStore.Address("tenant", "workflow", "run", "Counter");

    private static PersistentScalarMirV1.Plan compile() {
        return PersistentScalarMirV1.lower(SOURCE);
    }

    @Test
    void productionSourceLowersToCheckedScalarAndRestoresAcrossStoreReopen() throws Exception {
        var plan = compile();
        assertEquals("Counter", plan.actorName());
        assertEquals("count", plan.stateField());
        assertEquals(0L, plan.initialValue());
        assertEquals(8, plan.initial_snapshot().encodedState().length);

        Path database = temp.resolve("counter.db");
        try (var store = SqliteDurableActorStore.open(database)) {
            assertTrue(store.bootstrap(ID, plan.initial_snapshot()));
            try (var actor = PersistentScalarActorV1.open(plan, store, ID)) {
                assertEquals(0L, actor.get_count());
                assertEquals(1L, actor.increment("id-1", 1).orElseThrow());
                assertEquals(2L, actor.increment("id-2", 1).orElseThrow());
                assertEquals(2L, actor.get_count());
                assertEquals(2L, actor.state_version());
                assertEquals(OptionalLong.empty(), actor.increment("id-1", 1));
                assertThrows(IllegalStateException.class, () -> actor.increment("id-1", 100));
            }
        }
        try (var store = SqliteDurableActorStore.open(database)) {
            assertFalse(store.bootstrap(ID, plan.initial_snapshot()));
            try (var actor = PersistentScalarActorV1.open(plan, store, ID)) {
                assertEquals(2L, actor.get_count());
                assertEquals(3L, actor.increment("id-3", 1).orElseThrow());
                assertEquals(3L, actor.get_count());
                assertEquals(3L, actor.state_version());
            }
        }
    }

    @Test
    void replaysDurablyEnqueuedMessagesBeforePublishingReady() throws Exception {
        var plan = compile();
        try (var store = SqliteDurableActorStore.open(temp.resolve("inbox.db"))) {
            assertTrue(store.bootstrap(ID, plan.initial_snapshot()));
            assertTrue(store.enqueue(ID, new DurableActorStore.Inbound("first", plan.encode(4))));
            assertTrue(store.enqueue(ID, new DurableActorStore.Inbound("second", plan.encode(3))));
            try (var actor = PersistentScalarActorV1.open(plan, store, ID)) {
                assertEquals(7L, actor.get_count());
                assertEquals(2L, actor.state_version());
                assertEquals(OptionalLong.empty(), actor.increment("first", 4));
                assertThrows(IllegalStateException.class, () -> actor.increment("first", 400));
            }
            try (var actor = PersistentScalarActorV1.open(plan, store, ID)) {
                assertEquals(7L, actor.get_count());
                assertEquals(2L, actor.state_version());
            }
        }
    }

    @Test
    void failedCommitDoesNotPublishSpeculativeStateAndPoisonsOldEpoch() throws Exception {
        var plan = compile();
        Path database = temp.resolve("stale.db");
        try (var old = SqliteDurableActorStore.open(database);
             var current = SqliteDurableActorStore.open(database)) {
            old.bootstrap(ID, plan.initial_snapshot());
            var staleActor = PersistentScalarActorV1.open(plan, old, ID);
            try (var newActor = PersistentScalarActorV1.open(plan, current, ID)) {
                assertEquals(0L, newActor.get_count());
                assertThrows(IllegalStateException.class,
                        () -> staleActor.increment("stale-1", 99));
                assertThrows(IllegalStateException.class, staleActor::get_count);
            }
            // A stale actor is forbidden from enqueuing new work. Recovered
            // state must remain unchanged and no forged pending delivery may
            // appear under the newer activation.
            try (var recovered = PersistentScalarActorV1.open(plan, current, ID)) {
                assertEquals(0L, recovered.get_count());
                assertEquals(0L, recovered.state_version());
                assertEquals(1L, recovered.increment("legitimate", 1).orElseThrow());
            }
        }
    }

    @Test
    void fencingRevokesReadsAndVersionQueriesBeforeAnotherCommand() throws Exception {
        var plan = compile();
        Path db = temp.resolve("revoked-reads.db");
        try (var left = SqliteDurableActorStore.open(db);
             var right = SqliteDurableActorStore.open(db)) {
            left.bootstrap(ID, plan.initial_snapshot());
            var old = PersistentScalarActorV1.open(plan, left, ID);
            assertEquals(5L, old.increment("first", 5).orElseThrow());
            var next = PersistentScalarActorV1.open(plan, right, ID);
            assertEquals(5L, next.get_count());
            assertThrows(IllegalStateException.class, old::get_count);
            assertThrows(IllegalStateException.class, old::state_version);
            assertThrows(IllegalStateException.class,
                    () -> old.increment("late", 88));
            assertEquals(5L, next.get_count());
            next.close();
            var activation = right.activate(ID);
            assertTrue(activation.inbox().isEmpty(), "revoked writer must not enqueue");
            assertEquals(1L, activation.snapshot().stateVersion());
        }
    }

    @Test
    void concurrentFencedAdmissionRejectedWithoutMutatingCurrentInbox() throws Exception {
        var plan = compile();
        Path db = temp.resolve("fence-inbox.db");
        try (var older = SqliteDurableActorStore.open(db);
             var newer = SqliteDurableActorStore.open(db)) {
            older.bootstrap(ID, plan.initial_snapshot());
            long oldEpoch = older.activate(ID).epoch();
            long nextEpoch = newer.activate(ID).epoch();
            assertThrows(IllegalStateException.class,
                    () -> older.enqueueFenced(ID, oldEpoch,
                            new DurableActorStore.Inbound("late", plan.encode(77))));
            assertThrows(IllegalStateException.class,
                    () -> older.requireActive(ID, oldEpoch, 0));
            newer.requireActive(ID, nextEpoch, 0);
            assertTrue(newer.enqueueFenced(ID, nextEpoch,
                    new DurableActorStore.Inbound("valid", plan.encode(3))));
            assertFalse(newer.enqueueFenced(ID, nextEpoch,
                    new DurableActorStore.Inbound("valid", plan.encode(3))));
            assertEquals(List.of("valid"),
                    newer.activate(ID).inbox().stream().map(
                            DurableActorStore.Inbound::deliveryId).toList());
        }
    }

    @Test
    void rejectsOutOfOrderDeliveryInStorageTransaction() throws Exception {
        var plan = compile();
        try (var store = SqliteDurableActorStore.open(temp.resolve("ordered.db"))) {
            store.bootstrap(ID, plan.initial_snapshot());
            long epoch = store.activate(ID).epoch();
            store.enqueue(ID, new DurableActorStore.Inbound("first", plan.encode(2)));
            store.enqueue(ID, new DurableActorStore.Inbound("second", plan.encode(7)));
            assertThrows(IllegalStateException.class, () ->
                    store.commitTurn(new DurableActorStore.Turn(
                            ID, epoch, 0, "second", plan.snapshot(1, 7), List.of())));
            var seen = store.activate(ID);
            assertEquals(0L, seen.snapshot().stateVersion());
            assertEquals(List.of("first", "second"),
                    seen.inbox().stream().map(DurableActorStore.Inbound::deliveryId).toList());
            try (var replayed = PersistentScalarActorV1.open(plan, store, ID)) {
                assertEquals(9L, replayed.get_count());
            }
        }
    }

    @Test
    void rejectsMalformedOrWrongCodeSnapshotsBeforeRunningActor() throws Exception {
        var plan = compile();
        var wrongCode = new DurableActorStore.Snapshot(
                0, 1, "another-build", 0, plan.encode(3));
        assertThrows(IllegalStateException.class, () -> plan.decode(wrongCode));
        assertThrows(IllegalStateException.class, () -> plan.decode(new byte[] {1}));

        try (var store = SqliteDurableActorStore.open(temp.resolve("wrong-build.db"))) {
            store.bootstrap(ID, wrongCode);
            assertThrows(IllegalStateException.class,
                    () -> PersistentScalarActorV1.open(plan, store, ID));
        }
    }

    @Test
    void overflowDoesNotAcknowledgeOrLosePendingDelivery() throws Exception {
        var plan = compile();
        var initial = plan.snapshot(0, Long.MAX_VALUE);
        try (var store = SqliteDurableActorStore.open(temp.resolve("overflow.db"))) {
            store.bootstrap(ID, initial);
            var actor = PersistentScalarActorV1.open(plan, store, ID);
            assertThrows(ArithmeticException.class, () -> actor.increment("overflow", 1));
            assertThrows(IllegalStateException.class, actor::get_count);
            var activation = store.activate(ID);
            assertEquals(0, activation.snapshot().stateVersion());
            assertEquals(1, activation.inbox().size());
            assertEquals("overflow", activation.inbox().getFirst().deliveryId());
            assertThrows(ArithmeticException.class,
                    () -> PersistentScalarActorV1.open(plan, store, ID));
        }
    }

    @Test
    void actualOperatingSystemProcessCrashAndColdRestartPreservesCommittedCounter() throws Exception {
        Path path = temp.resolve("process.db");
        String before = runProcess("crash", path);
        assertTrue(before.contains("BEFORE_CRASH=2"), before);
        String after = runProcess("recover", path);
        assertTrue(after.contains("AFTER_RECOVERY=3"), after);
    }

    private static String runProcess(String mode, Path db) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(javaExecutable, "-cp", classpath,
                PersistentScalarCounterProcessMain.class.getName(),
                mode, db.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("persistent actor child process timeout");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void rejectsUnverifiedAndStateEscapingMethodBodies() {
        var valid = compile();
        assertEquals(valid.codeGeneration(), PersistentScalarMirV1.lower(SOURCE).codeGeneration());

        for (String altered : List.of(
                SOURCE.replace("critical-write", "critical-read"),
                SOURCE.replace("critical-read", "critical-write"),
                SOURCE.replace("s.count + amount", "s.count + 5"),
                SOURCE.replace("s.count = s.count + amount", "return s.count"),
                SOURCE.replace("s.count);", "s.nonexistent);"),
                SOURCE.replace("let int count: 0", "let String count: 0"),
                SOURCE.replace("s.count + amount", "await s.count + amount"),
                SOURCE.replace("return self.handle_state", "return unsafe_handle_state"),
                SOURCE.replace("get_count() -> int", "get_count(int x) -> int"),
                SOURCE.replace("return self.handle_state(critical-read |s| -> s.count);",
                        "return self.handle_state(critical-read |s| -> { s.count; });")
        )) {
            assertThrows(IllegalArgumentException.class,
                    () -> PersistentScalarMirV1.lower(altered),
                    () -> "unexpectedly admitted: " + altered);
        }
    }
}

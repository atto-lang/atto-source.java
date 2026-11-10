package dev.oreslang.runtime;

import dev.oreslang.compiler.PersistentScalarMirV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual ActorRuntime code-actor admission, mailbox, output and epoch fences. */
final class PersistentScalarActorRuntimeBridgeTest {
    @TempDir Path temporary;

    private static final DurableActorStore.Address KEY =
            new DurableActorStore.Address("tenant-a", "workflow", "run", "Counter");

    private static List<?> output(ActorRuntime.ActorRef<Object> actor) throws Exception {
        var step = actor.outputs().next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done(), "a durable receipt must be published");
        return assertInstanceOf(List.class, step.value().value());
    }

    @Test
    void realPrivateMailboxProcessesCommittedWritesAndReadsThenRecovers() throws Exception {
        Path path = temporary.resolve("runtime-integration.db");
        var plan = PersistentScalarMirV1.lower(PersistentScalarActorV1Test.SOURCE);
        try (var store = SqliteDurableActorStore.open(path);
             var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            store.bootstrap(KEY, plan.initial_snapshot());
            var compiler = PersistentScalarCodeExecutor.compile(
                    PersistentScalarActorV1Test.SOURCE, images, store, KEY);
            var actor = runtime.spawnPersistentScalarActor(compiler);
            assertEquals(ActorRuntime.ActorKind.PRIVATE, actor.kind());
            assertNull(actor.getReady().get(5, TimeUnit.SECONDS));
            assertSame(compiler.codeImage(), runtime.codeImageFor(actor).orElseThrow());

            actor.send(PersistentScalarCodeExecutor.increment_message("first", 1));
            assertEquals(List.of("first", 1L), output(actor));
            actor.send(PersistentScalarCodeExecutor.increment_message("second", 1));
            assertEquals(List.of("second", 2L), output(actor));
            actor.send(PersistentScalarCodeExecutor.get_count_message("read-1"));
            assertEquals(List.of("read-1", 2L), output(actor));
            assertNull(actor.gracefulStop().get(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), String.valueOf(actor.failure()));
        }
        try (var store = SqliteDurableActorStore.open(path);
             var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var compiler = PersistentScalarCodeExecutor.compile(
                    PersistentScalarActorV1Test.SOURCE, images, store, KEY);
            var actor = runtime.spawnPersistentScalarActor(compiler);
            assertNull(actor.ready().get(5, TimeUnit.SECONDS));
            actor.send(PersistentScalarCodeExecutor.get_count_message("recovered"));
            assertEquals(List.of("recovered", 2L), output(actor));
            actor.send(PersistentScalarCodeExecutor.increment_message("third", 1));
            assertEquals(List.of("third", 3L), output(actor));
            assertNull(actor.gracefulStop().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void replacementRuntimeFencesOldMailboxAndDoesNotAcceptLateDelivery() throws Exception {
        Path path = temporary.resolve("two-runtimes.db");
        var plan = PersistentScalarMirV1.lower(PersistentScalarActorV1Test.SOURCE);
        try (var oldStore = SqliteDurableActorStore.open(path);
             var newStore = SqliteDurableActorStore.open(path);
             var oldImages = new SharedCodeImageStore();
             var newImages = new SharedCodeImageStore();
             var oldRuntime = new ActorRuntime(IsolatePolicy.developer());
             var newRuntime = new ActorRuntime(IsolatePolicy.developer())) {
            oldStore.bootstrap(KEY, plan.initial_snapshot());
            var oldActor = oldRuntime.spawnPersistentScalarActor(
                    PersistentScalarCodeExecutor.compile(
                            PersistentScalarActorV1Test.SOURCE, oldImages, oldStore, KEY));
            assertNull(oldActor.ready().get(5, TimeUnit.SECONDS));
            oldActor.send(PersistentScalarCodeExecutor.increment_message("initial", 6));
            assertEquals(List.of("initial", 6L), output(oldActor));

            var newActor = newRuntime.spawnPersistentScalarActor(
                    PersistentScalarCodeExecutor.compile(
                            PersistentScalarActorV1Test.SOURCE, newImages, newStore, KEY));
            assertNull(newActor.ready().get(5, TimeUnit.SECONDS));
            newActor.send(PersistentScalarCodeExecutor.get_count_message("new"));
            assertEquals(List.of("new", 6L), output(newActor));

            // Invalidated actor remains physically allocated but MUST NOT read
            // stale state or enqueue a late durable command for its replacement.
            oldActor.send(PersistentScalarCodeExecutor.increment_message("late", 100));
            assertTrue(oldActor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(oldActor.failure().isPresent());
            newActor.send(PersistentScalarCodeExecutor.get_count_message("unchanged"));
            assertEquals(List.of("unchanged", 6L), output(newActor));
            assertNull(newActor.gracefulStop().get(5, TimeUnit.SECONDS));
            var observed = newStore.activate(KEY);
            assertTrue(observed.inbox().isEmpty(), "stale command not durably enqueued");
            assertEquals(1L, observed.snapshot().stateVersion());
        }
    }

    @Test
    void abruptOsProcessHaltThenColdActorRuntimeReactivation() throws Exception {
        Path database = temporary.resolve("actor-runtime-unclean.db");
        assertTrue(runSubprocess("crash", database).contains("RUNTIME_BEFORE_CRASH=2"));
        assertTrue(runSubprocess("recover", database).contains("RUNTIME_AFTER_RECOVERY=3"));
    }

    private static String runSubprocess(String mode, Path database) throws Exception {
        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(javaExecutable, "-cp", classpath,
                PersistentScalarRuntimeProcessMain.class.getName(),
                mode, database.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("subprocess ActorRuntime recovery timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void invalidMailboxCommandsFailWithoutMutatingDurableSnapshot() throws Exception {
        Path path = temporary.resolve("invalid-command.db");
        var plan = PersistentScalarMirV1.lower(PersistentScalarActorV1Test.SOURCE);
        try (var store = SqliteDurableActorStore.open(path);
             var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            store.bootstrap(KEY, plan.initial_snapshot());
            var executor = PersistentScalarCodeExecutor.compile(
                    PersistentScalarActorV1Test.SOURCE, images, store, KEY);
            var actor = runtime.spawnPersistentScalarActor(executor);
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send(List.of("increment", "not-a-number", "42"));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent());
            var activation = store.activate(KEY);
            assertEquals(0L, activation.snapshot().stateVersion());
            assertTrue(activation.inbox().isEmpty());
        }
    }

    @Test
    void sourceSubstitutionAndInvalidMessageIdsAreRejected() throws Exception {
        try (var store = SqliteDurableActorStore.open(temporary.resolve("invalid-source.db"));
             var images = new SharedCodeImageStore()) {
            assertThrows(IllegalArgumentException.class, () ->
                    PersistentScalarCodeExecutor.compile(
                        PersistentScalarActorV1Test.SOURCE.replace(
                                "critical-read", "critical-write"),
                        images, store, KEY));
            assertThrows(IllegalArgumentException.class, () ->
                    PersistentScalarCodeExecutor.increment_message(" ", 1));
            assertThrows(IllegalArgumentException.class, () ->
                    PersistentScalarCodeExecutor.get_count_message("x".repeat(257)));
        }
    }
}

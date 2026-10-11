package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorPipeTransportTest {
    private static final String STRING = String.class.getName();

    private static List<ActorPipelineContract.Stage> stages() {
        return List.of(
                new ActorPipelineContract.Stage("Source", ActorPipelineContract.Role.READABLE, null, STRING),
                new ActorPipelineContract.Stage("Transform", ActorPipelineContract.Role.TRANSFORM, STRING, STRING),
                new ActorPipelineContract.Stage("Sink", ActorPipelineContract.Role.WRITABLE, STRING, null));
    }

    @Test
    void createsSupervisedBoundedPipeAndPreservesOrder() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var daddy = runtime.createSupervisor();
            try (ActorPipeTransport pipe = ActorPipeTransport.open(
                    runtime, daddy, stages(), List.of(String.class, String.class), 2)) {
                assertEquals(daddy.id(), pipe.supervisorId());
                assertTrue(daddy.advisedGroupIds().contains(pipe.groupId()));
                var input = pipe.writer(0, String.class);
                var stageInput = pipe.reader(0, String.class);
                var stageOutput = pipe.writer(1, String.class);
                var output = pipe.reader(1, String.class);
                assertTrue(input.tryWrite("one"));
                assertTrue(input.tryWrite("two"));
                assertFalse(input.tryWrite("three"), "capacity must not silently expand");
                assertEquals("one", stageInput.readAsync().get(5, TimeUnit.SECONDS));
                assertEquals("two", stageInput.readAsync().get(5, TimeUnit.SECONDS));
                stageOutput.writeAsync("ONE").get(5, TimeUnit.SECONDS);
                assertEquals("ONE", output.readAsync().get(5, TimeUnit.SECONDS));
            }
            assertTrue(daddy.advisedGroupIds().isEmpty());
        }
    }

    @Test
    void awaitsBackpressureWithoutParkingThreadAndCancelsPendingSend() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            try (var pipe = ActorPipeTransport.open(runtime, runtime.rootSupervisor(),
                    stages(), List.of(String.class, String.class), 1)) {
                var writer = pipe.writer(0, String.class);
                var reader = pipe.reader(0, String.class);
                writer.writeAsync("first").get(5, TimeUnit.SECONDS);
                var waiting = writer.writeAsync("second");
                assertFalse(waiting.isDone(), "bounded pipe must backpressure upstream");
                var bypass = pipe.writer(0, String.class);
                assertThrows(ExecutionException.class,
                        () -> bypass.writeAsync("overflow").get(5, TimeUnit.SECONDS),
                        "fresh port handle must share the per-edge pending write budget");
                assertEquals("first", reader.readAsync().get(5, TimeUnit.SECONDS));
                waiting.get(5, TimeUnit.SECONDS);
                assertEquals("second", reader.readAsync().get(5, TimeUnit.SECONDS));
                writer.writeAsync("third").get(5, TimeUnit.SECONDS);
                var cancelled = writer.writeAsync("do-not-commit");
                assertTrue(cancelled.cancel(false), "waiting send cancellation should win");
                assertEquals("third", reader.readAsync().get(5, TimeUnit.SECONDS));
                assertTrue(reader.tryRead().isEmpty(), "cancelled send must not be admitted");
            }
        }
    }

    @Test
    void groupShutdownFencesAllPortsAndSettlesPendingWaiters() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var pipe = ActorPipeTransport.open(runtime, runtime.rootSupervisor(),
                    stages(), List.of(String.class, String.class), 1);
            var writer = pipe.writer(0, String.class);
            var priorReader = pipe.reader(0, String.class);
            writer.writeAsync("buffered").get(5, TimeUnit.SECONDS);
            var blocked = writer.writeAsync("blocked");
            assertFalse(blocked.isDone());
            runtime.actorGroup(pipe.groupId()).close();
            assertTrue(pipe.closed());
            assertThrows(ExecutionException.class, () -> blocked.get(5, TimeUnit.SECONDS));
            assertFalse(writer.tryWrite("post-close"));
            assertEquals("buffered", priorReader.readAsync().get(5, TimeUnit.SECONDS));
            pipe.close();
        }
    }

    @Test
    void rejectsWrongTypeAndForeignSupervisorBeforePublishingAnything() {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            assertThrows(IllegalArgumentException.class,
                    () -> ActorPipeTransport.open(runtime, runtime.rootSupervisor(),
                            stages(), List.of(String.class, Integer.class), 1));
            assertThrows(IllegalArgumentException.class,
                    () -> ActorPipeTransport.open(runtime, foreign.rootSupervisor(),
                            stages(), List.of(String.class, String.class), 1));
            assertEquals(0, runtime.actorGroupCount());
        }
    }

    @Test
    void keepsForwardAndReverseAuthorityDistinct() {
        try (ActorRuntime runtime = new ActorRuntime();
             var pipe = ActorPipeTransport.open(runtime, runtime.rootSupervisor(),
                     stages(), List.of(String.class, String.class), 1)) {
            assertThrows(IllegalArgumentException.class,
                    () -> pipe.writer(0, Integer.class));
            assertThrows(IllegalArgumentException.class,
                    () -> pipe.reader(0, Integer.class));
            assertEquals(2, pipe.connectionCount());
        }
    }
}

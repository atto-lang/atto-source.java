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
    void rejectsMutableObjectsAndOversizeScalarsAtHostBoundary() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            String listType = java.util.ArrayList.class.getName();
            var mutable = List.of(
                    new ActorPipelineContract.Stage("Source", ActorPipelineContract.Role.READABLE, null, listType),
                    new ActorPipelineContract.Stage("Sink", ActorPipelineContract.Role.WRITABLE, listType, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ActorPipeTransport.open(runtime, runtime.rootSupervisor(),
                            mutable, List.of(java.util.ArrayList.class), 1),
                    "mutable Java collections must not cross actor isolation domains");
            try (var pipe = ActorPipeTransport.open(runtime, runtime.rootSupervisor(),
                    stages(), List.of(String.class, String.class), 1)) {
                assertThrows(IllegalArgumentException.class,
                        () -> pipe.writer(0, String.class).writeAsync("x".repeat(1_048_577)),
                        "oversized string cannot allocate unbounded cross-actor payload storage");
            }
            String decimalType = java.math.BigDecimal.class.getName();
            var decimalStages = List.of(
                    new ActorPipelineContract.Stage("DecimalSource",
                            ActorPipelineContract.Role.READABLE, null, decimalType),
                    new ActorPipelineContract.Stage("DecimalSink",
                            ActorPipelineContract.Role.WRITABLE, decimalType, null));
            try (var decimalPipe = ActorPipeTransport.open(runtime, runtime.rootSupervisor(),
                    decimalStages, List.of(java.math.BigDecimal.class), 1)) {
                var writer = decimalPipe.writer(0, java.math.BigDecimal.class);
                assertThrows(IllegalArgumentException.class,
                        () -> writer.tryWrite(new java.math.BigDecimal(java.math.BigInteger.ONE,
                                Integer.MAX_VALUE)),
                        "huge positive decimal scale must not bypass payload budget");
                assertThrows(IllegalArgumentException.class,
                        () -> writer.tryWrite(new java.math.BigDecimal(java.math.BigInteger.ONE,
                                Integer.MIN_VALUE)),
                        "huge negative decimal scale must not bypass payload budget");
                assertTrue(writer.tryWrite(new java.math.BigDecimal("12.50")),
                        "ordinary immutable decimal scalars should still pass");
                assertEquals(new java.math.BigDecimal("12.50"),
                        decimalPipe.reader(0, java.math.BigDecimal.class).tryRead().orElseThrow());
                assertTrue(writer.tryWrite(new java.math.BigDecimal(java.math.BigInteger.ONE,
                                1_048_574)),
                        "exactly 1 MiB of plain decimal digits should be accepted");
                assertThrows(IllegalArgumentException.class,
                        () -> writer.tryWrite(new java.math.BigDecimal(java.math.BigInteger.ONE,
                                1_048_575)),
                        "one byte beyond the plain decimal limit must be rejected");
            }
        }
    }

    @Test
    void enforcesExactSignedBigIntegerPayloadByteBudget() {
        String integerType = java.math.BigInteger.class.getName();
        var integerStages = List.of(
                new ActorPipelineContract.Stage("BigSource",
                        ActorPipelineContract.Role.READABLE, null, integerType),
                new ActorPipelineContract.Stage("BigSink",
                        ActorPipelineContract.Role.WRITABLE, integerType, null));
        int limitBits = 1_048_576 * 8;
        java.math.BigInteger fits = java.math.BigInteger.ONE.shiftLeft(limitBits - 2);
        java.math.BigInteger oversize = java.math.BigInteger.ONE.shiftLeft(limitBits - 1);
        java.math.BigInteger negativeOversize =
                oversize.negate().subtract(java.math.BigInteger.ONE);
        assertEquals(1_048_576, fits.toByteArray().length);
        assertEquals(1_048_577, oversize.toByteArray().length);
        assertEquals(1_048_577, negativeOversize.toByteArray().length);
        try (ActorRuntime runtime = new ActorRuntime();
             ActorPipeTransport pipe = ActorPipeTransport.open(runtime,
                     runtime.rootSupervisor(), integerStages,
                     List.of(java.math.BigInteger.class), 1)) {
            var writer = pipe.writer(0, java.math.BigInteger.class);
            var reader = pipe.reader(0, java.math.BigInteger.class);
            assertTrue(writer.tryWrite(fits), "exact 1 MiB signed scalar is permitted");
            assertEquals(fits, reader.tryRead().orElseThrow());
            assertThrows(IllegalArgumentException.class,
                    () -> writer.tryWrite(oversize),
                    "positive scalar requiring 1 MiB plus one sign byte must be rejected");
            assertThrows(IllegalArgumentException.class,
                    () -> writer.tryWrite(negativeOversize),
                    "negative scalar requiring 1 MiB plus one byte must be rejected");
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

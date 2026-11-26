package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Host-only white-box proof of the new all-stage physical-DONE observer.
 * This deliberately settles mock DONE receipts without executing the guest.
 * Actual 15-stage guest execution is tested by ActorStreamPipeExecutionTest. */
final class ActorStreamPhysicalDoneBarrierTest {
    private static ActorRuntime.ActorStream stream(
            ActorRuntime owner,
            List<ActorRuntime.ActorRef<?>> stages,
            int phase) throws Exception {
        Class<?> routeType = Arrays.stream(ActorRuntime.class.getDeclaredClasses())
                .filter(t -> t.getSimpleName().equals("LinearPipeline"))
                .findFirst().orElseThrow();
        Constructor<?> routeCtor = routeType.getDeclaredConstructors()[0];
        routeCtor.setAccessible(true);
        Object route = routeCtor.newInstance(stages, "signed-nominal-test", new AtomicInteger(phase));
        Constructor<?> streamCtor = ActorRuntime.ActorStream.class.getDeclaredConstructors()[0];
        streamCtor.setAccessible(true);
        return (ActorRuntime.ActorStream) streamCtor.newInstance(owner, route);
    }

    @Test void sourceDoneCannotCompleteTheStreamBeforeBothOtherStages() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<?> a = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorRef<?> b = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorRef<?> c = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorStream stream = stream(runtime, List.of(a,b,c), 6);
            assertEquals(3, stream.size());
            a.done().completeFromRuntime(null);
            assertFalse(stream.getDone().isDone(), "source-only DONE is insufficient");
            b.done().completeFromRuntime(null);
            assertFalse(stream.getDone().isDone(), "physical sink DONE still required");
            c.done().completeFromRuntime(null);
            assertTrue(stream.getDone().isDone(), "all three DONE receipts required");
            assertFalse(stream.getDone().isCompletedExceptionally());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void sourceDoneCannotRemoveAdmissionFencesFromLiveDownstreamStages() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> source = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> { });
            ActorRuntime.ActorRef<Object> middle = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> { });
            ActorRuntime.ActorRef<Object> sink = runtime.spawnSharedTrusted(
                    ctx -> (message, turn) -> { });
            ActorRuntime.ActorStream stream = stream(runtime,
                    List.of(source, middle, sink), 6);

            var routeField = ActorRuntime.ActorStream.class.getDeclaredField("route");
            routeField.setAccessible(true);
            Object route = routeField.get(stream);
            var registryField = ActorRuntime.class.getDeclaredField("linearPipelines");
            registryField.setAccessible(true);
            var routes = (java.util.Map<ActorRuntime.ActorId, Object>)
                    registryField.get(runtime);
            for (var actor : List.of(source, middle, sink)) {
                routes.put(actor.id(), route);
            }

            // Deterministic physical-finalizer interleaving: source reaches
            // DONE first; downstream actor cells are still live. This is a
            // white-box invocation of the production finalizer callback.
            var cellType = Arrays.stream(ActorRuntime.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("ActorCell"))
                    .findFirst().orElseThrow();
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var cells = (java.util.Map<ActorRuntime.ActorId, ?>)
                    actorsField.get(runtime);
            var finalized = ActorRuntime.class.getDeclaredMethod(
                    "pipelineActorTerminated", cellType);
            finalized.setAccessible(true);
            finalized.invoke(runtime, cells.get(source.id()));

            assertFalse(routes.containsKey(source.id()));
            assertSame(route, routes.get(middle.id()),
                    "live middle stage must retain the routing admission fence");
            assertSame(route, routes.get(sink.id()),
                    "live sink must retain the routing admission fence");
            assertThrows(SecurityException.class, () -> middle.send("forged host DATA"));
            assertThrows(SecurityException.class, () -> sink.send("forged host DATA"));

            // Cleanup is owned by each sibling's own physical DONE event,
            // rather than prematurely by the source actor's finalizer.
            finalized.invoke(runtime, cells.get(middle.id()));
            assertFalse(routes.containsKey(middle.id()));
            assertSame(route, routes.get(sink.id()));
            finalized.invoke(runtime, cells.get(sink.id()));
            assertTrue(routes.isEmpty(), "last physical DONE releases final route fence");
        }
    }

    @Test void incompleteRoundtripRejectsEvenAfterAllPhysicalDoneReceipts() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<?> a = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorRef<?> b = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorRef<?> c = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorStream stream = stream(runtime, List.of(a,b,c), 5);
            a.done().completeFromRuntime(null);
            b.done().completeFromRuntime(null);
            c.done().completeFromRuntime(null);
            assertTrue(stream.getDone().isCompletedExceptionally(),
                    "terminal phase 5 cannot masquerade as completed phase 6");
        }
    }

    @Test void aStageFailureFailsTheStreamEvenBeforeOtherStagesFinish() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<?> a = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorRef<?> b = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorRef<?> c = runtime.spawnSharedTrusted(ctx -> (message, turn) -> {});
            ActorRuntime.ActorStream stream = stream(runtime, List.of(a,b,c), 2);
            b.done().failFromRuntime(new IllegalStateException("actor panicked"));
            assertTrue(stream.getDone().isCompletedExceptionally(),
                    "scheduler failures must reject immediately instead of hanging on sibling DONE");
        }
    }
}

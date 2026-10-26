package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class ActorPrivateMemoryIncarnationTest {

    @Test
    void sameIdReplacementCannotCaptureOrClosePrivateReservation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> live = runtime.spawnPrivate(
                    context -> (message, turn) -> { });
            live.ready().get(5, TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ActorRuntime.ActorId, Object> actors =
                    (Map<ActorRuntime.ActorId, Object>) actorsField.get(runtime);
            Object cell = actors.get(live.id());
            assertNotNull(cell);

            var sliceField = cell.getClass().getDeclaredField("memorySlice");
            sliceField.setAccessible(true);
            Object slice = sliceField.get(cell);
            assertNotNull(slice);

            var reserve = slice.getClass().getDeclaredMethod(
                    "reserve", long.class, String.class);
            reserve.setAccessible(true);
            Object reservation = reserve.invoke(slice, 8L, "exact-ref regression");

            assertThrows(IllegalStateException.class,
                    () -> ((AutoCloseable) reservation).close(),
                    "ambient host code must not retire an actor-owned reservation");

            var constructor = live.getClass().getDeclaredConstructor(
                    ActorRuntime.class,
                    ActorRuntime.ActorId.class,
                    ActorRuntime.ActorKind.class,
                    long.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<String> replacement =
                    (ActorRuntime.ActorRef<String>) constructor.newInstance(
                            runtime, live.id(), live.kind(), live.generation() + 1L);

            var validate = ActorRuntime.class.getDeclaredMethod(
                    "validatePrivateBehaviorCapture",
                    ActorRuntime.ActorRef.class,
                    String.class,
                    Object.class);
            validate.setAccessible(true);

            InvocationTargetException rejected = assertThrows(
                    InvocationTargetException.class,
                    () -> validate.invoke(runtime, replacement, "reservation", reservation));
            assertInstanceOf(SecurityException.class, rejected.getCause(),
                    "same logical id must not inherit private-memory release authority");

            live.stop();
            live.done().get(5, TimeUnit.SECONDS);
        }
    }
}

package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hostile host/interop regression for the runtime guard behind sealed
 * critical state access. Compiled Oreslang guest tests on #2245 separately
 * prove that valid actor-owned callbacks can read/write their sealed schema.
 */
final class ActorSealedStateAuthorityGateTest {
    @Test
    void unboundAndForgedActorObjectsCannotOpenStateLoanOutsideActorTurn()
            throws Exception {
        Class<?> objectType =
                Class.forName("dev.oreslang.nodes.OresEvalRootNode$OresObject");
        Constructor<?> constructor = objectType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        Class<?> viewType =
                Class.forName("dev.oreslang.nodes.OresEvalRootNode$ActorStateLoanView");
        Method open = viewType.getDeclaredMethod("open", objectType, boolean.class);
        open.setAccessible(true);

        // An object not initialized by ActorRuntime has no incarnation.
        Object unbound = constructor.newInstance(null, null, new LinkedHashMap<>());
        InvocationTargetException notOwned = assertThrows(
                InvocationTargetException.class,
                () -> open.invoke(null, unbound, true));
        assertInstanceOf(SecurityException.class, notOwned.getCause());

        // Even a forged or retained scalar actor ID is not sufficient:
        // admission must include the live ActorRuntime carrier authority.
        Object forged = constructor.newInstance(null, null, new LinkedHashMap<>());
        Field actorOwnerId = objectType.getDeclaredField("actorOwnerId");
        actorOwnerId.setAccessible(true);
        actorOwnerId.set(forged, new ActorRuntime.ActorId(UUID.randomUUID()));
        InvocationTargetException noCarrier = assertThrows(
                InvocationTargetException.class,
                () -> open.invoke(null, forged, false));
        assertInstanceOf(SecurityException.class, noCarrier.getCause());
        assertFalse(ActorRuntime.inActorExecution());
    }
}

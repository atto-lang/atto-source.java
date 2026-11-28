package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** White-box admission regression until a guest untrusted actor smoke fixture exists. */
class OresSymbolActorBoundaryTest {
    private static void checkInbound(Object graph) throws ReflectiveOperationException {
        Method validator = ActorRuntime.class.getDeclaredMethod(
                "rejectUntrustedInboundCapabilities",
                Object.class, IdentityHashMap.class, int.class);
        validator.setAccessible(true);
        validator.invoke(null, graph, new IdentityHashMap<>(), 0);
    }

    @Test void publicCoreSymbolCanAppearAsNestedKeyOrValue() {
        assertDoesNotThrow(() -> checkInbound(
                List.of(Map.of(OresSymbol.process("ready"), OresSymbol.process("error")))));
    }

    @Test void privateUserlandSymbolCannotBeLaunderedAsMapKey() {
        InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                () -> checkInbound(List.of(Map.of(
                        "metadata", Map.of(OresSymbol.process("tenant-a/private"), 3L)))));
        assertInstanceOf(SecurityException.class, failed.getCause());
    }

    @Test void privateUserlandSymbolCannotBeLaunderedAsMapValue() {
        InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                () -> checkInbound(Map.of("tag", OresSymbol.process("tenant-a/private"))));
        assertInstanceOf(SecurityException.class, failed.getCause());
    }

    @Test void untrustedInboundRejectsNestedActorGroupJoinCapability() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroupJoinCapability join =
                    runtime.createActorGroup().joinCapability();
            InvocationTargetException failed = assertThrows(
                    InvocationTargetException.class,
                    () -> checkInbound(List.of(Map.of("join", join))));
            assertInstanceOf(SecurityException.class, failed.getCause());
        }
    }

    @Test void untrustedInboundRejectsTypedMapEvenWhenPayloadLooksScalarSafe() {
        OresKeyedMap<String, Long> map =
                OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.INT);
        map.put("answer", 42L);
        InvocationTargetException failed = assertThrows(
                InvocationTargetException.class,
                () -> checkInbound(List.of(map)));
        assertInstanceOf(SecurityException.class, failed.getCause());
        assertTrue(failed.getCause().getMessage().contains("typed Map"));
    }

    @Test void stableUuidSymbolNotAutomaticallyExposedToUntrustedActor() {
        InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                () -> checkInbound(Map.of(
                        OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000"), 42L)));
        assertInstanceOf(SecurityException.class, failed.getCause());
    }
}

package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class CoreErrorDescriptorsTest {
    @Test void canonicalCatalogProvidesStableCodesAndExactObjects() {
        assertEquals(99, CoreErrorDescriptors.size());
        var root = CoreErrorDescriptors.requireKnown("Error");
        assertEquals(0, root.code());
        assertFalse(root.abstractType());
        assertSame(root, CoreErrorDescriptors.requireKnownCode(0));
        var timeout = CoreErrorDescriptors.requireKnown("ActorAskTimeoutError");
        assertEquals(9007, timeout.code());
        assertSame(timeout, CoreErrorDescriptors.requireKnownCode(9007));
        assertThrows(IllegalArgumentException.class,
                () -> CoreErrorDescriptors.requireKnown("ActorPanicError"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreErrorDescriptors.requireKnownCode(999999));
    }

    @Test void legacySupervisorSnapshotRejectsForgedNamesAndCodes() {
        var safe = GuestErrorWireCodec.supervisorCompactSnapshot(
                "ActorAskTimeoutError", 9007, Map.of("code", (Runnable) () -> {
                    throw new AssertionError("executed");
                }));
        assertEquals(9007, safe.code());
        assertFalse(safe.full());
        assertThrows(IllegalArgumentException.class, () ->
                GuestErrorWireCodec.supervisorCompactSnapshot(
                        "ActorAskTimeoutError", 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                GuestErrorWireCodec.supervisorCompactSnapshot(
                        "CustomFakeError", 9007, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                GuestErrorWireCodec.supervisorCompactSnapshot(
                        "ActorError", 9000, Map.of()));
    }

    @Test void supervisorUsesCoreDescriptorNotGuestFieldsOrGuestCodeMethods() {
        Map<String, Object> hostile = new java.util.HashMap<>();
        hostile.put("code", (Runnable) () -> { throw new AssertionError("executed"); });
        hostile.put("name", "ForgedError");
        hostile.put("message", "secret");
        hostile.put("stack", List.of("secret.ores"));
        hostile.put("self", hostile);
        var trusted = CoreErrorDescriptors.requireKnown("ActorAskTimeoutError");
        var snapshot = GuestErrorWireCodec.supervisorCompactCoreSnapshot(trusted, hostile);
        assertEquals("{\"schema\":1,\"code\":9007,\"name\":\"ActorAskTimeoutError\"}",
                snapshot.toJson());
        assertEquals(List.of(), snapshot.frames());
        assertEquals("", snapshot.message());
        assertFalse(snapshot.full());
        assertThrows(IllegalArgumentException.class,
                () -> GuestErrorWireCodec.supervisorCompactCoreSnapshot(
                        CoreErrorDescriptors.requireKnown("ActorError"), hostile));
    }
}

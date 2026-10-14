package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.interop.MixedSourceUnit;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class ActorErrorCatalogTest {
    @Test void contiguousPortableCodesAndTerminalPolicy() {
        ActorErrorCatalog.Kind[] kinds = ActorErrorCatalog.Kind.values();
        assertEquals(20, kinds.length);
        for (int i = 0; i < kinds.length; i++) {
            assertEquals(1001 + i, kinds[i].code());
            assertSame(kinds[i], ActorErrorCatalog.fromCode(1001 + i));
            assertFalse(kinds[i].wireName().isBlank());
        }
        assertTrue(ActorErrorCatalog.Kind.ACTOR_MAILBOX_FULL.recoverable());
        assertFalse(ActorErrorCatalog.Kind.ACTOR_CANCELLED.recoverable());
        assertFalse(ActorErrorCatalog.Kind.ACTOR_ISOLATION.recoverable());
        assertThrows(IllegalArgumentException.class, () -> ActorErrorCatalog.fromCode(1000));
        assertThrows(IllegalArgumentException.class, () -> ActorErrorCatalog.fromCode(1021));
    }

    @Test void actorEnvelopeIsValueOnlyAndBounded() {
        var envelope = new ActorErrorCatalog.Envelope(
                ActorErrorCatalog.Kind.ACTOR_SEND_REJECTED,
                "é".repeat(900) + "\uD800",
                "actor.1", "supervisor:2");
        assertEquals(1004, envelope.code());
        assertTrue(envelope.diagnostic().getBytes(StandardCharsets.UTF_8).length <= 1024);
        assertTrue(envelope.diagnostic().endsWith("é"));
        assertThrows(IllegalArgumentException.class, () -> new ActorErrorCatalog.Envelope(
                ActorErrorCatalog.Kind.ACTOR_STOPPED, "message", "../bad/id", ""));
        assertThrows(IllegalArgumentException.class, () -> new ActorErrorCatalog.Envelope(
                ActorErrorCatalog.Kind.ACTOR_STOPPED, "message", "a".repeat(129), ""));
        assertEquals("?", ActorErrorCatalog.boundedDiagnostic("\uD800"));
    }

    @Test void sourceFirstActorErrorsArePackagedWithoutHostInterop() throws Exception {
        String src = CoreLibrarySources.source("std/actor_errors");
        assertTrue(src.contains("class ActorError extends Error"));
        for (ActorErrorCatalog.Kind kind : ActorErrorCatalog.Kind.values()) {
            assertTrue(src.contains(" "+kind.code()+" "), "guest source must declare code "+kind.code());
        }
        var unit = MixedSourceUnit.parse(CoreLibrarySources.unitId("std/actor_errors"),
                CoreLibrarySources.fileName("std/actor_errors"), src);
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertFalse(src.contains("java:"));
    }
}

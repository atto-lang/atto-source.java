package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresSymbol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SymbolKeyFoundationTest {
    @Test
    void canonicalNameAndKindDetermineIdentityWithoutPermanentInterning() {
        OresSymbol first = OresSymbol.process("café");
        OresSymbol decomposed = OresSymbol.process("cafe\u0301");
        assertEquals(first, decomposed);
        assertEquals(first.hashCode(), decomposed.hashCode());
        assertNotSame(first, decomposed); // no promise of shared object address
        assertNotEquals(OresSymbol.process("ready"), "ready");
        assertNotEquals(OresSymbol.process("ready"), OresSymbol.process("Ready"));
        String uuid = UUID.randomUUID().toString();
        assertNotEquals(OresSymbol.process(uuid), OresSymbol.stable(uuid));
    }

    @Test
    void symbolAndStringKeysNeverAliasInJavaMapBacking() {
        Map<Object, Integer> keyed = new HashMap<>();
        keyed.put("foo", 1);
        keyed.put(OresSymbol.process("foo"), 2);
        assertEquals(2, keyed.size());
        assertEquals(1, keyed.get("foo"));
        assertEquals(2, keyed.get(OresSymbol.process("foo")));
        assertNull(keyed.get(OresSymbol.process("bar")));
        assertEquals("#foo", OresSymbol.process("foo").toString());
        assertEquals("#\"ready foo\"", OresSymbol.process("ready foo").toString());
    }

    @Test
    void duplicateCanonicalSymbolKeysCollapseButDistinctStringKeyRemains() {
        Map<Object, String> keyed = new HashMap<>();
        keyed.put(OresSymbol.process("café"), "first");
        keyed.put(OresSymbol.process("cafe\u0301"), "second");
        keyed.put("café", "string");
        assertEquals(2, keyed.size());
        assertEquals("second", keyed.get(OresSymbol.process("café")));
        assertEquals("string", keyed.get("café"));
    }

    @Test
    void rejectsMalformedOrOversizedNamesAndUntrustedWireAliases() {
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process(""));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("x\u0000y"));
        assertThrows(IllegalArgumentException.class, () -> OresSymbol.process("x\u202Ey"));
        assertThrows(IllegalArgumentException.class, () ->
                OresSymbol.fromWire(OresSymbol.Kind.PROCESS, "cafe\u0301"));
        assertEquals(OresSymbol.process("café"),
                OresSymbol.fromWire(OresSymbol.Kind.PROCESS, "café"));
        assertThrows(IllegalArgumentException.class, () ->
                OresSymbol.stable("00000000-0000-0000-0000-00000000000A"));
    }

    @Test
    void onlyPublicCoreSymbolsCrossUntrustedMailboxesIncludingNestedMaps() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Object> observed = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> untrusted = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> {
                        observed.set(message);
                        delivered.countDown();
                        turn.self().stop();
                    });
            assertThrows(SecurityException.class, () ->
                    untrusted.send(OresSymbol.process("userland/private")));
            assertThrows(SecurityException.class, () ->
                    untrusted.send(Map.of("nested", List.of(OresSymbol.process("secret")))));
            untrusted.send(Map.of(OresSymbol.process("ready"), "ok"));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            Map<?, ?> value = assertInstanceOf(Map.class, observed.get());
            assertEquals("ok", value.get(OresSymbol.process("ready")));
        }
    }

    @Test
    void ordinaryActorCanFreezeImmutableSymbolKeyedMapsWithoutConvertingKeys() {
        Map<Object, Object> map = new HashMap<>();
        map.put("ready", 1);
        map.put(OresSymbol.process("ready"), 2);
        Map<?, ?> frozen = assertInstanceOf(Map.class, ActorRuntime.freeze(map));
        assertEquals(2, frozen.size());
        assertEquals(1, frozen.get("ready"));
        assertEquals(2, frozen.get(OresSymbol.process("ready")));
    }
}

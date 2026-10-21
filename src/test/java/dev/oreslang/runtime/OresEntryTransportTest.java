package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresEntryTransportTest {
    @Test
    void freezePreservesNominalEntryAndDeepFreezesChildren() {
        ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
        OresEntry source = new OresEntry("numbers", mutable);

        Object frozenValue = ActorRuntime.freeze(source);
        OresEntry frozen = assertInstanceOf(OresEntry.class, frozenValue);
        assertNotSame(source, frozen);
        assertEquals("numbers", frozen.key());
        assertEquals(List.of(1, 2), frozen.value());

        mutable.add(3);
        assertEquals(List.of(1, 2), frozen.value());
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((List<Object>) frozen.value()).add(4));
    }

    @Test
    void privateActorIsolationPreservesNominalEntryTag() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<OresEntry> observed = new AtomicReference<>();

            var ref = runtime.<OresEntry>spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, context) -> {
                        observed.set(message);
                        received.countDown();
                        context.self().stop();
                    });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(7, 9));
            OresEntry sent = new OresEntry("pair", mutable);
            ref.send(sent);
            mutable.add(11);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            OresEntry receivedEntry = observed.get();
            assertNotNull(receivedEntry);
            assertNotSame(sent, receivedEntry);
            assertEquals("pair", receivedEntry.key());
            assertEquals(List.of(7, 9), receivedEntry.value());
        }
    }

    @Test
    void entryChildrenStillUndergoActorCapabilityValidation() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var recipient = runtime.<String>spawn(() -> (message, context) -> context.self().stop());

            OresEntry hiddenInKey = new OresEntry(recipient, "value");
            OresEntry hiddenInValue = new OresEntry("ref", recipient);
            OresEntry nested = new OresEntry(
                    "outer",
                    new OresEntry(recipient, "nested"));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(hiddenInKey),
                    "data-only freeze must not hide an ActorRef in Entry.key");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(hiddenInValue),
                    "data-only freeze must not hide an ActorRef in Entry.value");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(nested),
                    "nested Entry values must not hide actor capabilities");
            recipient.stop();
        }
    }

    @Test
    void entryNestingHonorsActorGraphDepthBoundary() {
        Object atLimit = "leaf";
        for (int i = 0; i < 256; i++) {
            atLimit = new OresEntry(i, atLimit);
        }
        Object allowed = atLimit;
        assertDoesNotThrow(() -> ActorRuntime.freeze(allowed));

        Object beyondLimit = new OresEntry("overflow", atLimit);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(beyondLimit));
        assertTrue(rejected.getMessage().contains("maximum nesting depth"),
                rejected.getMessage());
    }
    @Test
    void subclassedBigIntegerCannotMasqueradeAsActorScalar() {
        java.util.concurrent.atomic.AtomicInteger callbacks =
                new java.util.concurrent.atomic.AtomicInteger();
        final class HostileBigInteger extends java.math.BigInteger {
            HostileBigInteger() {
                super("7");
            }

            @Override
            public byte[] toByteArray() {
                callbacks.incrementAndGet();
                throw new AssertionError("host BigInteger.toByteArray invoked");
            }
        }

        OresEntry entry = new OresEntry("number", new HostileBigInteger());
        IllegalArgumentException frozen = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(entry));
        assertTrue(frozen.getMessage().contains("not Sendable"), frozen.getMessage());
        assertEquals(0, callbacks.get());

        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<OresEntry>spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    context -> (message, actor) -> actor.self().stop());
            IllegalArgumentException sent = assertThrows(
                    IllegalArgumentException.class,
                    () -> target.send(entry));
            assertTrue(sent.getMessage().contains("not Sendable"), sent.getMessage());
            assertEquals(0, callbacks.get());
            target.stop();
        }
    }

    @Test
    void hostileHostCollectionsAreRejectedWithoutCallbacks() {
        java.util.concurrent.atomic.AtomicInteger callbacks =
                new java.util.concurrent.atomic.AtomicInteger();

        final class HostileList extends ArrayList<Object> {
            @Override public int size() {
                callbacks.incrementAndGet();
                throw new AssertionError("host List.size invoked");
            }
            @Override public java.util.Iterator<Object> iterator() {
                callbacks.incrementAndGet();
                throw new AssertionError("host List.iterator invoked");
            }
        }

        final class HostileMap extends java.util.LinkedHashMap<Object, Object> {
            @Override public int size() {
                callbacks.incrementAndGet();
                throw new AssertionError("host Map.size invoked");
            }
            @Override public java.util.Set<java.util.Map.Entry<Object, Object>> entrySet() {
                callbacks.incrementAndGet();
                throw new AssertionError("host Map.entrySet invoked");
            }
        }

        HostileList hostileList = new HostileList();
        hostileList.add("x");
        HostileMap hostileMap = new HostileMap();
        hostileMap.put("k", "v");

        for (Object hostile : List.of(hostileList, hostileMap)) {
            OresEntry entry = new OresEntry("payload", hostile);
            IllegalArgumentException frozen = assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(entry));
            assertTrue(frozen.getMessage().contains("not Sendable"), frozen.getMessage());
            assertEquals(0, callbacks.get());

            try (ActorRuntime runtime = new ActorRuntime()) {
                var target = runtime.<OresEntry>spawnPrivate(
                        IsolatePolicy.strictFaas(),
                        context -> (message, actor) -> actor.self().stop());
                IllegalArgumentException sent = assertThrows(
                        IllegalArgumentException.class,
                        () -> target.send(entry));
                assertTrue(sent.getMessage().contains("not Sendable"), sent.getMessage());
                assertEquals(0, callbacks.get());
                target.stop();
            }
        }
    }

    @Test
    void exactJdkGuestCollectionCarriersRemainSendable() {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("list", new ArrayList<>(List.of(1, 2, 3)));
        payload.put("set", new java.util.LinkedHashSet<>(List.of("a", "b")));

        OresEntry frozen = assertInstanceOf(
                OresEntry.class,
                ActorRuntime.freeze(new OresEntry("containers", payload)));
        assertEquals("containers", frozen.key());
        assertInstanceOf(ActorRuntime.FrozenGuestMap.class, frozen.value());
    }

    @Test
    void cyclicHostPayloadIsRejectedBeforeActorFreezeRatherThanOverflowing() {
        ArrayList<Object> cyclic = new ArrayList<>();
        OresEntry entry = new OresEntry(cyclic, "payload");
        cyclic.add(entry);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(entry));
        assertTrue(rejected.getMessage().contains("cyclic")
                        || rejected.getMessage().contains("cycle"),
                rejected.getMessage());
    }

}

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
            OresEntry entry = new OresEntry("ref", recipient);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorRuntime.freeze(entry),
                    "data-only freeze must not hide an ActorRef inside Entry");
            recipient.stop();
        }
    }
}

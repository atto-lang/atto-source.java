package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-level actor DATA snapshot regression. This tests the existing
 * ActorRuntime mailbox copy/freeze boundary, not compiler-derived codecs.
 */
@Timeout(20)
final class ActorDataSnapshotIsolationTest {

    @Test
    void sharedMailboxOwnsIndependentFrozenNestedGraph() throws Exception {
        AtomicReference<List<?>> received = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<List<?>>spawnShared(() ->
                    (message, context) -> received.set(message));
            ArrayList<String> nested = new ArrayList<>(List.of("before"));
            ArrayList<Object> sender = new ArrayList<>();
            sender.add(nested);
            sender.add("stable");

            ref.send(sender);
            nested.set(0, "after");
            sender.set(1, "changed");
            ref.drain().get(5, TimeUnit.SECONDS);

            List<?> receiver = received.get();
            assertNotNull(receiver);
            assertNotSame(sender, receiver);
            assertEquals(List.of(List.of("before"), "stable"), receiver);
            assertNotSame(nested, receiver.get(0));
            assertThrows(UnsupportedOperationException.class,
                    () -> ((List<Object>) receiver).add("injected"));
            assertThrows(UnsupportedOperationException.class,
                    () -> ((List<Object>) receiver.get(0)).add("injected"));
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void privateMailboxHasIndependentNestedGraph() throws Exception {
        AtomicReference<List<?>> received = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<List<?>>spawnPrivate(() ->
                    (message, context) -> received.set(message));
            ArrayList<String> nested = new ArrayList<>(List.of("before"));
            ArrayList<Object> sender = new ArrayList<>();
            sender.add(nested);
            sender.add("stable");

            ref.send(sender);
            nested.set(0, "after");
            sender.set(1, "changed");
            ref.drain().get(5, TimeUnit.SECONDS);

            List<?> receiver = received.get();
            assertNotNull(receiver);
            assertNotSame(sender, receiver);
            assertEquals(List.of(List.of("before"), "stable"), receiver);
            assertNotSame(nested, receiver.get(0));
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedNestedNonDataLeavesOwnershipAndMailboxRecoverable() throws Exception {
        AtomicReference<List<?>> received = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<List<?>>spawnPrivate(() ->
                    (message, context) -> received.set(message));
            Object unportable = new Object();
            ArrayList<String> ownedNested = new ArrayList<>(List.of("before"));
            ArrayList<Object> sender = new ArrayList<>();
            sender.add(ownedNested);
            sender.add(List.of(unportable));

            // A nested host reference is not DATA. A failed admission must
            // neither steal/mutate sender ownership nor poison mailbox state.
            assertThrows(IllegalArgumentException.class, () -> ref.send(sender));
            assertSame(ownedNested, sender.get(0));
            assertSame(unportable, ((List<?>) sender.get(1)).get(0));
            ownedNested.add("still-owned");

            // Repair in place and reuse the same sender container; the next
            // valid send must reach the actor as a separate owned snapshot.
            sender.set(1, "repaired");
            ref.send(sender);
            ownedNested.add("after-send");
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(List.of("before", "still-owned"), "repaired"),
                    received.get());
            assertNotSame(sender, received.get());
            assertNotSame(ownedNested, received.get().get(0));
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sendFailureDoesNotConsumeSenderGraph() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<List<?>>spawnShared(() -> (message, context) -> { });
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);

            ArrayList<String> nested = new ArrayList<>(List.of("owned"));
            ArrayList<Object> sender = new ArrayList<>(List.of(nested));
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> ref.send(sender));
            assertEquals(List.of(List.of("owned")), sender);
            nested.add("still-owned");
            assertEquals(List.of("owned", "still-owned"), nested);
        }
    }
}

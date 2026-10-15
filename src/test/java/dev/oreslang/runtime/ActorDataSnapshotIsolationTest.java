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

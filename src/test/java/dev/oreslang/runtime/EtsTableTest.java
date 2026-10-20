package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class EtsTableTest {
    @Test
    void setIsBoundedFrozenAndSupportsAtomicPointOperations() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            EtsTable<String, List<String>> table =
                    runtime.createEtsTable(2, EtsTable.Access.PUBLIC);

            ArrayList<String> source = new ArrayList<>();
            source.add("one");
            assertTrue(table.put("a", source));
            source.add("mutated-after-put");

            assertEquals(List.of("one"), table.get("a").orElseThrow());
            assertFalse(table.putIfAbsent("a", List.of("ignored")));
            assertTrue(table.compareAndSet(
                    "a", List.of("one"), List.of("replacement")));
            assertFalse(table.compareAndSet(
                    "a", List.of("one"), List.of("wrong")));
            assertEquals(List.of("replacement"), table.get("a").orElseThrow());

            assertTrue(table.putIfAbsent("b", List.of("two")));
            assertThrows(
                    IllegalStateException.class,
                    () -> table.put("c", List.of("three")));
            assertEquals(2, table.size());

            assertTrue(table.remove("b"));
            assertTrue(table.put("c", List.of("three")));
            assertEquals(
                    List.of("replacement"),
                    table.snapshot().get("a"));

            table.close();
            assertTrue(table.closed());
            assertThrows(IllegalStateException.class, () -> table.get("a"));
        }
    }

    @Test
    void protectedAndPrivateAccessPoliciesDoNotLeakSharedMutation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            EtsTable<String, String> protectedTable =
                    runtime.createEtsTable(4, EtsTable.Access.PROTECTED);
            EtsTable<String, String> privateTable =
                    runtime.createEtsTable(4, EtsTable.Access.PRIVATE);
            protectedTable.put("visible", "value");
            privateTable.put("secret", "value");

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertEquals(
                                    "value",
                                    protectedTable.get("visible").orElseThrow());
                            assertThrows(
                                    SecurityException.class,
                                    () -> protectedTable.put("blocked", "write"));
                            assertThrows(
                                    SecurityException.class,
                                    () -> privateTable.get("secret"));
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            done.countDown();
                        }
                    });

            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorOwnedTableClosesWhenOwnerRetires() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch created = new CountDownLatch(1);
            AtomicReference<EtsTable<String, String>> tableRef =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> owner = runtime.spawnPrivateTrusted(
                    factory -> (message, context) -> {
                        try {
                            EtsTable<String, String> table =
                                    context.runtime().createEtsTable(
                                            4, EtsTable.Access.PRIVATE);
                            table.put("owner", "alive");
                            tableRef.set(table);
                            created.countDown();
                            context.self().stop();
                        } catch (Throwable problem) {
                            failure.set(problem);
                            created.countDown();
                        }
                    });

            owner.send(1);
            assertTrue(created.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));

            EtsTable<String, String> table = tableRef.get();
            assertNotNull(table);
            assertTrue(table.closed(), "owner retirement must close its ETS table");
            assertThrows(IllegalStateException.class, () -> table.get("owner"));
        }
    }

    @Test
    void tableCapabilityCannotBeUsedFromAnotherRuntimeActor() throws Exception {
        try (ActorRuntime first = new ActorRuntime();
             ActorRuntime second = new ActorRuntime()) {
            EtsTable<String, String> table =
                    first.createEtsTable(2, EtsTable.Access.PUBLIC);
            table.put("k", "v");

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor = second.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertThrows(
                                    SecurityException.class,
                                    () -> table.get("k"));
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

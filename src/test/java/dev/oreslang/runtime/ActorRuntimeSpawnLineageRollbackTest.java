package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime parent/child registry rollback after an injected
 * collection failure that occurs AFTER child insertion. Failure must not
 * strand lineage, an actor slot, or an unpublished physical ActorRef.
 */
@Timeout(40)
final class ActorRuntimeSpawnLineageRollbackTest {
    private static Field access(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void failedChildInsertionAfterMutationDoesNotOrphanSpawnLineage()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var childAdmissionFailed = new CompletableFuture<Boolean>();
            var parent = runtime.<Integer>spawnShared(
                    IsolatePolicy.developer(), () -> (message, context) -> {
                        try {
                            context.runtime().<Integer>spawnShared(
                                    childContext -> (value, child) -> { });
                            childAdmissionFailed.completeExceptionally(
                                    new AssertionError("injected child insertion should fail"));
                        } catch (OutOfMemoryError injected) {
                            childAdmissionFailed.complete(
                                    "injected parent child insertion failure"
                                            .equals(injected.getMessage()));
                        }
                    });
            parent.getReady().get(5, TimeUnit.SECONDS);
            @SuppressWarnings("unchecked")
            Map<Object, Object> actors =
                    (Map<Object, Object>) access(ActorRuntime.class, "actors").get(runtime);
            Object parentCell = actors.get(parent.id());
            assertNotNull(parentCell);
            Field childrenField = access(parentCell.getClass(), "children");
            @SuppressWarnings("unchecked")
            Set<Object> original = (Set<Object>) childrenField.get(parentCell);
            var trigger = new AtomicBoolean(true);
            Set<Object> failing = new AbstractSet<>() {
                private final Set<Object> delegate = ConcurrentHashMap.newKeySet();

                @Override public Iterator<Object> iterator() {
                    return delegate.iterator();
                }

                @Override public int size() {
                    return delegate.size();
                }

                @Override public boolean add(Object child) {
                    boolean added = delegate.add(child);
                    if (added && trigger.getAndSet(false)) {
                        throw new OutOfMemoryError(
                                "injected parent child insertion failure");
                    }
                    return added;
                }

                @Override public boolean remove(Object child) {
                    return delegate.remove(child);
                }
            };
            assertTrue(original.isEmpty());
            childrenField.set(parentCell, failing);
            try {
                parent.send(1);
                assertTrue(childAdmissionFailed.get(5, TimeUnit.SECONDS));
                assertTrue(failing.isEmpty(),
                        "rollback must unlink a child even if insertion threw after mutation");
                assertEquals(1, actors.size(),
                        "failed child admission must not publish a ghost actor");
                AtomicInteger count = (AtomicInteger) access(
                        ActorRuntime.class, "actorCount").get(runtime);
                assertEquals(1, count.get(),
                        "failed child admission must refund the actor slot");
            } finally {
                // Restore only after the parent turn signaled completion.
                childrenField.set(parentCell, original);
            }
            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

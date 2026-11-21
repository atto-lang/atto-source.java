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

    @Test
    void failedRootRegistryInsertionAfterMutationDoesNotStrandActorSlot()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            Field registryField = access(ActorRuntime.class, "actors");
            @SuppressWarnings("unchecked")
            Map<Object, Object> original =
                    (Map<Object, Object>) registryField.get(runtime);
            assertTrue(original.isEmpty());
            var injected = new AtomicBoolean(true);
            var registry = new ConcurrentHashMap<Object, Object>() {
                @Override public Object put(Object key, Object value) {
                    Object previous = super.put(key, value);
                    if (injected.getAndSet(false)) {
                        throw new OutOfMemoryError(
                                "injected registry failure after publication");
                    }
                    return previous;
                }
            };
            registryField.set(runtime, registry);
            try {
                assertThrows(OutOfMemoryError.class,
                        () -> runtime.<Integer>spawnShared(
                                () -> (message, actor) -> { }));
                assertEquals(0, registry.size(),
                        "failed spawn cannot strand a registered physical actor");
                AtomicInteger count = (AtomicInteger) access(
                        ActorRuntime.class, "actorCount").get(runtime);
                assertEquals(0, count.get(),
                        "failure after registry publication must refund actor count");
            } finally {
                registryField.set(runtime, original);
            }

            // A subsequent legitimate root actor must be fully schedulable.
            var recovery = runtime.<Integer>spawnShared(
                    () -> (message, actor) -> { });
            recovery.getReady().get(5, TimeUnit.SECONDS);
            recovery.stop();
            assertTrue(recovery.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedRegistryRemovalAfterMutationStillUnlinksParentAndRefundsSlot()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var observed = new CompletableFuture<Boolean>();
            var parent = runtime.<Integer>spawnShared(
                    IsolatePolicy.developer(), () -> (message, context) -> {
                        try {
                            context.runtime().<Integer>spawnShared(
                                    child -> (value, childContext) -> { });
                            observed.completeExceptionally(
                                    new AssertionError("faulted child insertion must fail"));
                        } catch (OutOfMemoryError injected) {
                            observed.complete(
                                    "injected after-child registry put".equals(
                                            injected.getMessage()));
                        }
                    });
            parent.getReady().get(5, TimeUnit.SECONDS);
            Field actorsField = access(ActorRuntime.class, "actors");
            @SuppressWarnings("unchecked")
            Map<Object, Object> original =
                    (Map<Object, Object>) actorsField.get(runtime);
            Object parentCell = original.get(parent.id());
            assertNotNull(parentCell);
            Field childrenField = access(parentCell.getClass(), "children");
            @SuppressWarnings("unchecked")
            Set<Object> children = (Set<Object>) childrenField.get(parentCell);
            assertTrue(children.isEmpty());

            var enabled = new AtomicBoolean(false);
            var failedRemoval = new AtomicBoolean(false);
            var injectedRegistry = new ConcurrentHashMap<Object, Object>() {
                @Override public Object put(Object key, Object value) {
                    Object prior = super.put(key, value);
                    if (enabled.get()) {
                        throw new OutOfMemoryError(
                                "injected after-child registry put");
                    }
                    return prior;
                }
                @Override public boolean remove(Object key, Object value) {
                    boolean removed = super.remove(key, value);
                    if (removed && enabled.get()
                            && failedRemoval.compareAndSet(false, true)) {
                        throw new OutOfMemoryError(
                                "injected after-child registry remove");
                    }
                    return removed;
                }
            };
            injectedRegistry.putAll(original);
            enabled.set(true);
            actorsField.set(runtime, injectedRegistry);
            try {
                parent.send(1);
                assertTrue(observed.get(5, TimeUnit.SECONDS));
                assertTrue(failedRemoval.get(),
                        "fixture must exercise a cleanup failure after mutation");
                assertEquals(1, injectedRegistry.size(),
                        "failed child registry entry must be removed");
                assertTrue(children.isEmpty(),
                        "registry removal error cannot skip parent lineage rollback");
                AtomicInteger count = (AtomicInteger) access(
                        ActorRuntime.class, "actorCount").get(runtime);
                assertEquals(1, count.get(),
                        "failed child actor slot must be refunded despite cleanup failure");
            } finally {
                actorsField.set(runtime, original);
            }
            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void failedRegistryRemovalBeforeMutationRetainsLiveChildLineage()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var observed = new CompletableFuture<Boolean>();
            var parent = runtime.<Integer>spawnShared(
                    IsolatePolicy.developer(), () -> (message, context) -> {
                        try {
                            context.runtime().<Integer>spawnShared(
                                    child -> (value, childContext) -> { });
                            observed.completeExceptionally(
                                    new AssertionError("child admission must fail"));
                        } catch (OutOfMemoryError expected) {
                            observed.complete("after registered child put"
                                    .equals(expected.getMessage()));
                        }
                    });
            parent.getReady().get(5, TimeUnit.SECONDS);
            Field registryField = access(ActorRuntime.class, "actors");
            @SuppressWarnings("unchecked")
            Map<Object, Object> original =
                    (Map<Object, Object>) registryField.get(runtime);
            var failPut = new AtomicBoolean(false);
            var failRemove = new AtomicBoolean(false);
            var registry = new ConcurrentHashMap<Object, Object>() {
                @Override public Object put(Object key, Object value) {
                    Object previous = super.put(key, value);
                    if (failPut.compareAndSet(true, false))
                        throw new OutOfMemoryError("after registered child put");
                    return previous;
                }

                @Override public boolean remove(Object key, Object value) {
                    if (failRemove.compareAndSet(true, false))
                        throw new OutOfMemoryError("before registered child removal");
                    return super.remove(key, value);
                }
            };
            registry.putAll(original);
            failPut.set(true);
            failRemove.set(true);
            registryField.set(runtime, registry);
            try {
                parent.send(1);
                assertTrue(observed.get(5, TimeUnit.SECONDS));
                assertFalse(failRemove.get(),
                        "runtime cleanup must attempt exact registry removal");
                assertEquals(2, registry.size(),
                        "failed pre-mutation removal must retain child registry owner");
                assertEquals(1, parent.childIds().size(),
                        "live registered child must remain in parent's lifecycle set");
                AtomicInteger count = (AtomicInteger) access(
                        ActorRuntime.class, "actorCount").get(runtime);
                assertEquals(2, count.get(),
                        "registered child must retain its runtime actor slot");

                ActorRuntime.ActorRef<?> child = null;
                for (Object cell : registry.values()) {
                    ActorRuntime.ActorRef<?> candidate =
                            (ActorRuntime.ActorRef<?>) access(
                                    cell.getClass(), "ref").get(cell);
                    if (!candidate.id().equals(parent.id())) child = candidate;
                }
                assertNotNull(child, "retain exact unreturned physical child");
                child.stop();
                assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(1, count.get(),
                        "physical finalization refunds the child slot once");
                assertTrue(parent.childIds().isEmpty(),
                        "physical finalization owns parent unlink");
                assertEquals(1, registry.size());
            } finally {
                registryField.set(runtime, original);
            }
            parent.stop();
            assertTrue(parent.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

}

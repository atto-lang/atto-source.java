package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A -> B -> C has one lifecycle owner, not one new supervisor or ActorGroup
 * per nested spawn. Cooperative event-bus membership is never implicit.
 */
@Timeout(15)
final class ActorNestedGroupInheritanceTest {
    @Test
    void managedParentPassesLifecycleOwnerToChildAndGrandchild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroup unrelated = runtime.createActorGroup();

            ActorRuntime.ActorRef<String> root = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> (message, context) -> {
                        if (!"make-child".equals(message)) return;
                        ActorRuntime.ActorRef<String> child =
                                context.runtime().spawnPrivate(
                                        childFactory -> (childMessage, childContext) -> {
                                            if (!"make-grandchild".equals(childMessage)) return;
                                            childContext.runtime().spawnPrivate(
                                                    grandFactory -> (grandMessage, grandContext) -> { });
                                        });
                        child.send("make-grandchild");
                    });

            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("make-child");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (group.workerCount() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(3, group.workerCount(),
                    "a grandchild must inherit the same managed ActorGroup recursively");

            List<ActorRuntime.ActorRef<?>> family = group.workers();
            Set<ActorRuntime.ActorId> ids = family.stream()
                    .map(ActorRuntime.ActorRef::id)
                    .collect(Collectors.toSet());
            assertEquals(3, ids.size());
            assertTrue(ids.contains(root.id()));
            assertEquals(0, group.memberCount(),
                    "lifecycle ownership must not imply cooperative event-bus membership");
            for (ActorRuntime.ActorRef<?> actor : family) {
                assertFalse(group.contains(actor));
                assertThrows(IllegalStateException.class,
                        () -> unrelated.manageWorker(actor),
                        "a second group must not take structured lifecycle ownership");
            }

            group.close();
            for (ActorRuntime.ActorRef<?> actor : family) {
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                        "group close must retire the entire inherited family");
            }
            unrelated.close();
        }
    }

    @Test
    void ungroupedNestedSpawnDoesNotInventAnEventBusGroup() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> root = runtime.spawnPrivate(
                    factory -> (message, context) -> {
                        context.runtime().spawnPrivate(
                                childFactory -> (childMessage, childContext) -> { });
                    });
            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("make-child");
            // A host-created actor outside a managed group retains existing
            // structured descendant cancellation but creates no implicit
            // cooperative ActorGroup or event-bus capability.
            assertEquals(0, runtime.actorGroupCount());
            root.stop();
            assertTrue(root.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

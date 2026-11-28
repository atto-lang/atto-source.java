package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multithreaded supervisor membership churn must publish immutable,
 * identity-safe snapshots without blocking user DATA routing carriers.
 */
@Timeout(25)
final class ActorPipelineActorGroupSnapshotTest {
    @Test
    void concurrentGroupJoinsNeverPublishForgedOrMutableEligibility()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            var actors = new ArrayList<ActorRuntime.ActorRef<String>>();
            var ids = new HashSet<ActorRuntime.ActorId>();
            for (int i = 0; i < 8; i++) {
                var worker = runtime.<String>spawnShared(() -> (value, context) -> { });
                actors.add(worker);
                ids.add(worker.id());
            }
            Set<ActorRuntime.ActorId> allowed = Set.copyOf(ids);
            var first = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var threads = new ArrayList<Thread>();

            for (int reader = 0; reader < 4; reader++) {
                threads.add(Thread.ofPlatform().name("group-route-reader-" + reader).start(() -> {
                    try {
                        if (!first.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("reader barrier timeout");
                        for (int i = 0; i < 5000; i++) {
                            var snapshot = group.pipelineMemberSnapshot();
                            if (!allowed.containsAll(snapshot))
                                throw new AssertionError("foreign ActorId admitted to route snapshot");
                            // Every published view is structurally immutable;
                            // no caller can mutate it to add authorization.
                            if (i == 0) {
                                try {
                                    snapshot.add(actors.getFirst().id());
                                    throw new AssertionError("member snapshot was mutable");
                                } catch (UnsupportedOperationException expected) { }
                            }
                        }
                    } catch (Throwable failure) {
                        error.compareAndSet(null, failure);
                    }
                }));
            }
            for (int shard = 0; shard < 2; shard++) {
                final int offset = shard;
                threads.add(Thread.ofPlatform().name("group-route-joiner-" + shard).start(() -> {
                    try {
                        if (!first.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("join barrier timeout");
                        for (int i = offset; i < actors.size(); i += 2)
                            group.join(actors.get(i), join);
                    } catch (Throwable failure) {
                        error.compareAndSet(null, failure);
                    }
                }));
            }
            first.countDown();
            for (var thread : threads) {
                thread.join(7000);
                assertFalse(thread.isAlive(), "snapshot stress thread failed to terminate");
            }
            if (error.get() != null)
                throw new AssertionError("concurrent ActorGroup snapshot assertion", error.get());
            assertEquals(allowed, group.pipelineMemberSnapshot(),
                    "eventually all joined real actor identities must be selectable");
            assertEquals(8, group.memberCount());

            group.close();
            assertThrows(IllegalStateException.class, group::pipelineMemberSnapshot,
                    "closed group must fail routing selection");
            for (var worker : actors)
                assertTrue(worker.isAlive(),
                        "cooperative ActorGroup membership must not imply worker ownership");
            for (var worker : actors) worker.stop();
            for (var worker : actors)
                worker.getDone().get(5, TimeUnit.SECONDS);
        }
    }
}

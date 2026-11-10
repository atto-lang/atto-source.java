package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authenticated guest state permits may not be minted by a supervisor,
 * detached callback, or arbitrary host actor. The source guest positive
 * route is exercised in ActorCriticalStateModeTest and conformance fixtures.
 */
@Timeout(30)
final class ActorStateRuntimePermitAuthorityTest {

    @Test
    void stateLoanViewKeepsTheExecutionAndStackIdentity() {
        ExecutionContext caller = ExecutionContext.actor(19, 23, 31, 37, 41, 43, 47);
        ExecutionContext first = caller.actorStateView(31, 37, 41);
        ExecutionContext second = caller.actorStateView(31, 37, 41);
        assertSame(caller.id(), first.id());
        assertSame(caller.id(), second.id());
        assertEquals(first.executionHandle(), second.executionHandle());
        assertEquals(caller.allocationDomain(), first.allocationDomain());
        assertEquals(43, first.parentExecution());
        assertEquals(47, first.continuation());
        assertThrows(SecurityException.class, () -> caller.actorStateView(31, 99, 41));
    }

    @Test
    void unboundTaskViewAttachesActorAllocationWithoutMintingAnExecution() {
        ExecutionContext caller = ExecutionContext.task(53, 59);
        ExecutionContext view = caller.actorStateView(61, 67, 71);
        assertSame(caller.id(), view.id());
        assertEquals(ExecutionContext.AllocationKind.NONE, caller.allocationDomain().kind());
        assertEquals(ExecutionContext.AllocationKind.ACTOR, view.allocationDomain().kind());
        assertEquals(59, view.executionHandle().slot());
    }

    @Test
    void supervisorCannotForgeActorStatePermit() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            assertThrows(SecurityException.class,
                    () -> runtime.acquireCurrentActorStatePermit(false));
            assertThrows(SecurityException.class,
                    () -> runtime.acquireCurrentActorStatePermit(true));
        }
    }

    @Test
    void ordinaryHostActorCannotForgeSourceOwnedSealedStatePermit()
            throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicBoolean rejectedRead = new AtomicBoolean();
        AtomicBoolean rejectedWrite = new AtomicBoolean();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> context.runtime().acquireCurrentActorStatePermit(false));
                    rejectedRead.set(true);
                    assertThrows(SecurityException.class,
                            () -> context.runtime().acquireCurrentActorStatePermit(true));
                    rejectedWrite.set(true);
                } finally {
                    attempted.countDown();
                }
            });
            actor.send(7);
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            assertTrue(rejectedRead.get());
            assertTrue(rejectedWrite.get());
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}

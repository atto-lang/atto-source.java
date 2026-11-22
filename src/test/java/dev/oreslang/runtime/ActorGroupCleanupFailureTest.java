package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorGroupCleanupFailureTest {
    @Test
    void infrastructureErrorDoesNotStrandLaterHooksOrRegistryCleanup() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            AtomicInteger laterHook = new AtomicInteger();

            group.registerInfrastructureClose(() -> {
                throw new AssertionError("synthetic infrastructure teardown failure");
            });
            group.registerInfrastructureClose(laterHook::incrementAndGet);

            AssertionError failure = assertThrows(AssertionError.class, group::close);
            assertEquals("synthetic infrastructure teardown failure", failure.getMessage());
            assertEquals(1, laterHook.get(),
                    "one failing infrastructure hook must not strand later cleanup");
            assertTrue(group.closed());
            assertEquals(0, runtime.actorGroupCount(),
                    "group registry removal must complete before the original Error is rethrown");
        }
    }


    @Test
    void multipleInfrastructureFailuresPreservePrimaryAndStillRunLaterHooks() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            AtomicInteger laterHook = new AtomicInteger();

            group.registerInfrastructureClose(() -> {
                throw new IllegalStateException("primary cleanup failure");
            });
            group.registerInfrastructureClose(() -> {
                throw new AssertionError("secondary cleanup failure");
            });
            group.registerInfrastructureClose(laterHook::incrementAndGet);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, group::close);
            assertEquals("primary cleanup failure", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(AssertionError.class, failure.getSuppressed()[0]);
            assertEquals("secondary cleanup failure",
                    failure.getSuppressed()[0].getMessage());
            assertEquals(1, laterHook.get(),
                    "later cleanup hooks must run after independent unchecked failures");
            assertTrue(group.closed());
            assertEquals(0, runtime.actorGroupCount(),
                    "multi-failure cleanup must still retire the group registry");
        }
    }


    @Test
    void runtimeCloseAggregatesGroupAndPolicyErrorsAndStillInvalidatesSharedState() {
        ActorRuntime runtime = new ActorRuntime();
        ActorRuntime.ActorGroup group = runtime.createActorGroup();
        group.registerInfrastructureClose(() -> {
            throw new IllegalStateException("synthetic group cleanup failure");
        });

        AtomicInteger policyReclaims = new AtomicInteger();
        SupervisorCodeSlot<String> code = new SupervisorCodeSlot<>(
                "ABI", "root-v1", () -> {
                    policyReclaims.incrementAndGet();
                    throw new AssertionError("synthetic policy cleanup failure");
                }, 2);
        runtime.rootSupervisor().installPolicyCode(code);

        ActorRuntime.SyncCell<String> cell = runtime.syncCell("live");
        ActorRuntime.Shared<String> shared = runtime.shareReadonly("shared");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, runtime::close);
        assertEquals("synthetic group cleanup failure", failure.getMessage());
        assertEquals(1, policyReclaims.get(),
                "policy cleanup must still run after an ActorGroup cleanup failure");
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(AssertionError.class, failure.getSuppressed()[0]);
        assertEquals("synthetic policy cleanup failure",
                failure.getSuppressed()[0].getMessage());

        assertTrue(group.closed());
        assertEquals(0, runtime.actorGroupCount());
        assertTrue(cell.closed(),
                "SyncCell invalidation must not be stranded by earlier cleanup failures");
        assertThrows(IllegalStateException.class, shared::value,
                "readonly shared values must still be invalidated during failed shutdown");

        runtime.close();
    }


    @Test
    void fatalRuntimeCleanupFencesRetainedHandlesBeforeLaterTeardown() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        ActorRuntime.ActorGroup group = runtime.createActorGroup();
        group.registerInfrastructureClose(() -> {
            throw new IllegalStateException("ordinary group cleanup failure");
        });

        SupervisorCodeSlot<String> code = new SupervisorCodeSlot<>(
                "ABI", "root-fatal-fence", () -> {
                    throw new LinkageError("fatal policy cleanup failure");
                }, 2);
        runtime.rootSupervisor().installPolicyCode(code);

        ActorRuntime.SyncCell<String> cell = runtime.syncCell("live");
        ActorRuntime.Shared<String> shared = runtime.shareReadonly("shared");
        ActorRuntime.ActorRef<Object> actor =
                runtime.spawn(() -> (message, context) -> { });
        ActorRuntime.ActorOutputStream<Object> outputs = actor.outputs();

        LinkageError failure = assertThrows(LinkageError.class, runtime::close);
        assertEquals("fatal policy cleanup failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, failure.getSuppressed()[0]);
        assertEquals("ordinary group cleanup failure",
                failure.getSuppressed()[0].getMessage());

        assertTrue(group.closed(),
                "runtime shutdown must fence ActorGroup authority after fatal cleanup");
        assertTrue(cell.closed(),
                "SyncCell must observe committed runtime shutdown even if teardown was skipped");
        assertThrows(IllegalStateException.class, cell::snapshot);
        assertThrows(IllegalStateException.class, shared::value);
        assertTrue(outputs.isDone(),
                "actor output stream must stop exposing queued output after runtime shutdown");
        assertTrue(outputs.next().get().done(),
                "pulling a retained output stream after runtime shutdown must return DONE");
    }


    @Test
    void laterVmFatalRetainsEarlierGroupCleanupFailure() {
        ActorRuntime runtime = new ActorRuntime();
        ActorRuntime.ActorGroup group = runtime.createActorGroup();
        AtomicInteger laterHook = new AtomicInteger();

        group.registerInfrastructureClose(() -> {
            throw new IllegalStateException("ordinary cleanup failure");
        });
        group.registerInfrastructureClose(() -> {
            throw new LinkageError("vm fatal cleanup failure");
        });
        group.registerInfrastructureClose(laterHook::incrementAndGet);

        LinkageError failure = assertThrows(LinkageError.class, group::close);
        assertEquals("vm fatal cleanup failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, failure.getSuppressed()[0]);
        assertEquals("ordinary cleanup failure", failure.getSuppressed()[0].getMessage());
        assertEquals(0, laterHook.get());
        assertTrue(group.closed());
        assertEquals(0, runtime.actorGroupCount());

        runtime.close();
    }


    @Test
    void fatalJvmCleanupErrorsRemainFailFast() {
        ActorRuntime runtime = new ActorRuntime();
        ActorRuntime.ActorGroup group = runtime.createActorGroup();
        AtomicInteger laterHook = new AtomicInteger();

        group.registerInfrastructureClose(() -> {
            throw new LinkageError("synthetic fatal cleanup failure");
        });
        group.registerInfrastructureClose(laterHook::incrementAndGet);

        LinkageError failure = assertThrows(LinkageError.class, group::close);
        assertEquals("synthetic fatal cleanup failure", failure.getMessage());
        assertEquals(0, laterHook.get(),
                "fatal JVM errors must not be normalized into ordinary cleanup aggregation");
        assertTrue(group.closed());
        assertEquals(0, runtime.actorGroupCount(),
                "a fail-fast fatal cleanup must still retire the closed group publication");

        runtime.close();
    }
    @Test
    void cleanupFailureAggregationIsBoundedButStillRunsEveryHook() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            AtomicInteger attempted = new AtomicInteger();
            for (int i = 0; i < 96; ++i) {
                final int index = i;
                group.registerInfrastructureClose(() -> {
                    attempted.incrementAndGet();
                    throw new IllegalStateException("cleanup-" + index);
                });
            }

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, group::close);
            assertEquals("cleanup-0", failure.getMessage());
            assertEquals(96, attempted.get(),
                    "diagnostic bounding must never stop later cleanup work");
            assertTrue(failure.getSuppressed().length <= 64,
                    "cleanup diagnostics must remain bounded under failure storms");
            assertTrue(group.closed());
            assertEquals(0, runtime.actorGroupCount());
        }
    }


}

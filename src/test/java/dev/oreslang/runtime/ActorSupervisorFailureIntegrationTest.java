package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class ActorSupervisorFailureIntegrationTest {
    private static final class SyntheticGuestPanic extends GuestFailureSignal {
        SyntheticGuestPanic() { super(SupervisorFailureLane.Kind.PANIC, "guest-panic"); }
    }

    @Test
    void actorPanicLeavesRootAliveAndPublishesTypedControlBeforeDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var broken = runtime.<Integer>spawn(() -> (value, context) -> {
                throw new SyntheticGuestPanic();
            });
            broken.send(1);
            assertTrue(broken.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(broken.failure().isPresent());
            var event = runtime.pollSupervisionFailure().orElseThrow();
            assertEquals(SupervisorFailureLane.Kind.PANIC, event.kind());
            assertEquals(broken.id().value().toString(), event.actor().id());
            assertEquals("", event.parentId());
            assertTrue(event.summary().contains("guest-panic"));
            assertEquals(0, runtime.pendingSupervisionFailures());

            // A failed child cannot poison a sibling's scheduler or terminate root.
            CountDownLatch healthy = new CountDownLatch(1);
            var sibling = runtime.<Integer>spawn(() -> (value, context) -> {
                healthy.countDown();
                context.self().stop();
            });
            sibling.send(2);
            assertTrue(healthy.await(5, TimeUnit.SECONDS));
            assertTrue(sibling.awaitTermination(5, TimeUnit.SECONDS));

            runtime.retrySupervisionFailure(event.actor());
            assertEquals(1, runtime.pendingSupervisionFailures());
            assertEquals(event, runtime.pollSupervisionFailure().orElseThrow());
            runtime.acknowledgeSupervisionFailure(event.actor());
            assertTrue(runtime.pollSupervisionFailure().isEmpty());
        }
    }

    @Test
    void ordinaryJavaFailuresAreNotMislabeledAsGuestThrowOrPanic() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var broken = runtime.<String>spawn(() -> (value, context) -> {
                throw new IllegalStateException("host-side bug");
            });
            broken.send("fail");
            assertTrue(broken.awaitTermination(5, TimeUnit.SECONDS));
            var event = runtime.pollSupervisionFailure().orElseThrow();
            assertEquals(SupervisorFailureLane.Kind.HOST_FAILURE, event.kind());
            runtime.acknowledgeSupervisionFailure(event.actor());
        }
    }

    @Test
    void urgentFailureCannotBeStarvedBehindUserMailbox() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var broken = runtime.<Integer>spawn(() -> (value, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                }
                throw new SyntheticGuestPanic();
            });
            broken.send(0);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                for (int i = 1; i <= 256; i++) broken.send(i);
            } finally {
                release.countDown();
            }
            assertTrue(broken.awaitTermination(5, TimeUnit.SECONDS));
            var control = runtime.pollSupervisionFailure().orElseThrow();
            assertEquals(SupervisorFailureLane.Kind.PANIC, control.kind());
            runtime.acknowledgeSupervisionFailure(control.actor());
        }
    }
}

package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ChannelBackpressureDomainIntegrationTest {
    private static IsolatePolicy mailboxPolicy(int messages) {
        IsolatePolicy base = IsolatePolicy.developer();
        return new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                messages,
                base.maxWallTime(),
                base.adversarial());
    }

    @Test
    void userMailboxKeepsExactPhysicalBoundWhileInternalChannelsUseRuntimeDomain()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(
                    mailboxPolicy(3),
                    () -> (message, context) -> {
                        if (message == 1) {
                            firstEntered.countDown();
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("held actor turn timed out");
                            }
                        }
                    });

            assertEquals(
                    3,
                    runtime.mailboxPhysicalCapacity(ref),
                    "USER Channel must not regain CONTROL continuation headroom");
            assertTrue(
                    runtime.actorChannelsUseRuntimeAdmissionDomain(ref),
                    "mailbox/continuation/output Channels must charge the ActorRuntime domain");

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            ref.send(3);
            ref.send(4);

            assertThrows(
                    ActorRuntime.ActorMailboxFullException.class,
                    () -> ref.send(5),
                    "fourth queued USER message must not fit behind hidden CONTROL headroom");

            OresFuture<Void> pending = ref.sendAsync(6);
            assertFalse(
                    pending.isDone(),
                    "awaitable producer should suspend instead of bypassing the USER bound");

            releaseFirst.countDown();
            pending.get(5, TimeUnit.SECONDS);
            ref.drain().get(5, TimeUnit.SECONDS);

            assertEquals(
                    0,
                    runtime.channelAdmissionDomain().pendingSelections(),
                    "mailbox admission must not leak generic Channel select registrations");

            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }
}

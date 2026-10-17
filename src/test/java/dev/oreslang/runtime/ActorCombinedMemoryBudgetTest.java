package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class ActorCombinedMemoryBudgetTest {
    private static final long ACTOR_LIMIT = 16L * 1024 * 1024;
    private static final int PAYLOAD = 9 * 1024 * 1024;

    private static IsolatePolicy constrainedActorPolicy() {
        var baseline = IsolatePolicy.developer();
        return new IsolatePolicy(
                baseline.capabilities(),
                ACTOR_LIMIT,
                4,
                Duration.ofMinutes(1),
                false);
    }

    @Test
    void sharedMailboxCannotOvercommitBytesAlreadyHeldByOutput() throws Exception {
        var outputPublished = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<byte[]>spawnShared(
                    constrainedActorPolicy(),
                    () -> (message, context) -> {
                        context.runtime().emitCurrentActorOutput(new byte[PAYLOAD]);
                        outputPublished.countDown();
                    });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(new byte[1]);
            assertTrue(outputPublished.await(5, TimeUnit.SECONDS));

            var before = runtime.mailboxSnapshot(actor).orElseThrow();
            assertTrue(before.accountedActorBytes() >= PAYLOAD);
            assertEquals(ACTOR_LIMIT, before.policyHeapLimitBytes());

            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> actor.send(new byte[PAYLOAD]));
            assertTrue(
                    failure.getMessage().contains("combined memory limit"),
                    () -> failure.getMessage());

            var after = runtime.mailboxSnapshot(actor).orElseThrow();
            assertEquals(
                    before.accountedActorBytes(),
                    after.accountedActorBytes(),
                    "rejected mailbox reservation must not leak actor bytes");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void privateMailboxCannotOvercommitBytesAlreadyHeldByOutput() throws Exception {
        var outputPublished = new CountDownLatch(1);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var actor = runtime.<byte[]>spawnPrivateTrusted(
                    constrainedActorPolicy(),
                    context -> (message, turn) -> {
                        turn.runtime().emitCurrentActorOutput(new byte[PAYLOAD]);
                        outputPublished.countDown();
                    });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(new byte[1]);
            assertTrue(outputPublished.await(5, TimeUnit.SECONDS));

            var before = runtime.mailboxSnapshot(actor).orElseThrow();
            assertTrue(before.accountedActorBytes() >= PAYLOAD);
            assertEquals(ACTOR_LIMIT, before.policyHeapLimitBytes());

            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> actor.send(new byte[PAYLOAD]));
            assertTrue(
                    failure.getMessage().contains("combined memory limit"),
                    () -> failure.getMessage());

            var after = runtime.mailboxSnapshot(actor).orElseThrow();
            assertEquals(
                    before.accountedActorBytes(),
                    after.accountedActorBytes(),
                    "rejected private mailbox reservation must not leak actor bytes");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

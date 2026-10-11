package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorChildFailureControlTest {
    @Test
    void parentReceivesDirectChildPanicWithoutUserMailOrStoppingParent() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);

            var parent = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        if (!message.equals("spawn")) return;
                        var child = context.runtime().<String>spawnShared(
                                childFactory -> (childMessage, childContext) -> {
                                    throw new OresPanic("child failed");
                                });
                        childRef.set(child);
                        created.countDown();
                        child.send("crash");
                    });
            // Subscribe before any child exists to detect lost wakeups.
            var first = parent.nextChildFailure();
            assertFalse(first.cancel(false));
            assertThrows(IllegalStateException.class, parent::nextChildFailure);
            parent.send("spawn");
            assertTrue(created.await(5, TimeUnit.SECONDS));
            var notice = first.get(5, TimeUnit.SECONDS);
            assertEquals(childRef.get().id(), notice.actorId());
            assertEquals(ActorRuntime.ActorFailureKind.PANIC, notice.kind());
            assertEquals("child failed", notice.summary());
            assertTrue(parent.isAlive(), "child failure must not kill parent");
            parent.stop();
        }
    }
}

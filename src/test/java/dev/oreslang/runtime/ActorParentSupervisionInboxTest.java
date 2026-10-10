package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Failure transport must be independent of the parent's user mailbox. */
@Timeout(20)
final class ActorParentSupervisionInboxTest {
    @Test
    void parentReceivesTypedChildPanicEvenWhenFailureFinalizesChildFirst() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(() -> (command, parentContext) -> {
                var child = parentContext.runtime().<String>spawnShared(
                        childContext -> (message, context) -> {
                            throw new OresPanic("child crashed");
                        });
                child.send("panic");
            });
            // Subscribe before child exists: parent CONTROL lane is not user mail.
            var noticeFuture = parent.nextChildFailure();
            assertFalse(noticeFuture.cancel(false));
            assertThrows(IllegalStateException.class, parent::nextChildFailure,
                    "overlapping parent failure readers must fail closed");

            parent.send("spawn");
            var notice = noticeFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ActorRuntime.ActorFailureKind.PANIC, notice.kind());
            assertEquals("child crashed", notice.summary());
            assertTrue(parent.isAlive(), "parent must survive a failed child");
        }
    }

    @Test
    void childFailureIsBufferedWithoutPendingParentReader() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(() -> (command, parentContext) -> {
                var child = parentContext.runtime().<String>spawnShared(
                        childContext -> (message, context) -> {
                            throw new OresRaised("escalated");
                        });
                child.send("raise");
            });
            parent.ready().get(5, TimeUnit.SECONDS);
            parent.send("spawn");

            // Ordinary actor data turns and a missing parent waiter must not
            // prevent the terminal notice from becoming available.
            var notice = parent.nextChildFailure().get(5, TimeUnit.SECONDS);
            assertEquals(ActorRuntime.ActorFailureKind.RAISE, notice.kind());
            assertEquals("escalated", notice.summary());
            assertTrue(parent.isAlive());
        }
    }

    @Test
    void failedConstructorAlsoReportsToParentsControlPlane() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnShared(() -> (command, parentContext) -> {
                var child = parentContext.runtime().<String>spawnShared(
                        (ActorRuntime.BehaviorFactory<String>) context -> {
                            throw new OresPanic("constructor panicked");
                        });
                child.ready();
            });
            var noticeFuture = parent.nextChildFailure();
            parent.send("spawn");
            var notice = noticeFuture.get(5, TimeUnit.SECONDS);
            assertEquals(ActorRuntime.ActorFailureKind.PANIC, notice.kind());
            assertEquals("constructor panicked", notice.summary());
            assertTrue(parent.isAlive());
        }
    }
}

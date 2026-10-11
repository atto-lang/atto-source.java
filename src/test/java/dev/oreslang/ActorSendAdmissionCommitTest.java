package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Local mailbox admission is not retroactively revoked by a carrier failure.
 * This test forces the otherwise rare accepted-then-dispatch-rejected race.
 */
final class ActorSendAdmissionCommitTest {
    @Test
    void acceptedMessageDoesNotBecomeRejectedSendIfCarrierSubmissionFails() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<Integer>spawnShared(
                    () -> (message, context) -> fail("dispatcher was shut down"));

            // Test-only fault injection. No production introspection or public
            // carrier shutdown hook is exposed to source actors.
            Field sharedDispatcher = ActorRuntime.class.getDeclaredField("sharedDispatcher");
            sharedDispatcher.setAccessible(true);
            ExecutorService dispatcher = (ExecutorService) sharedDispatcher.get(runtime);
            dispatcher.shutdownNow();

            assertDoesNotThrow(() -> worker.send(42),
                    "the mailbox commit happened before scheduling was rejected");

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> worker.getDone().get(2, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause(),
                    "submission failure must be retained as actor failure");
            assertTrue(worker.failure().isPresent());
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> worker.send(43),
                    "a subsequent send into the sealed mailbox must fail admission");
        }
    }
}

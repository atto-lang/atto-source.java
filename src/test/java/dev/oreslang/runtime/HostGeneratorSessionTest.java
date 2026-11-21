package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class HostGeneratorSessionTest {
    private static final String SOURCE = """
            async fnc pending(): int { return 44; }
            async generator fnc numbers(): int {
              try {
                yield 11;
                yield 22;
                yield 33;
                yield await pending();
              } catch (err) {
              } finally {
                val int cleanup_marker = 1;
              }
              return;
            }
            """;

    @Test
    void javaHostPullsThreeTypedSourceValuesAndCancelsPendingFourth()
            throws Exception {
        OresFuture<Integer> pendingSourceCall = new OresFuture<>();
        try (OresScheduler root = new OresScheduler(2)) {
            HostGeneratorSession session = HostGeneratorSession.compile(
                    root, () -> OresScheduler.current() == root,
                    SOURCE, "numbers", Map.of("pending", pendingSourceCall));

            // Java host calls these directly. The root scheduler, not this
            // thread or a virtual-thread generator producer, runs the guest.
            for (int expected : new int[]{11, 22, 33}) {
                var pull = session.nextStep();
                var step = pull.get(5, TimeUnit.SECONDS);
                assertFalse(step.done());
                assertEquals(expected, ((Number) step.value()).intValue());
            }
            var fourth = session.nextStep();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!session.awaitingSourceFuture() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(session.awaitingSourceFuture(),
                    "fourth source pull must actually reach its suspended await");
            assertEquals(0, session.cleanupRuns());
            assertFalse(fourth.isDone(), "fourth source await must remain pending");
            assertTrue(fourth.cancel(false));
            session.seal();
            root.close();
            assertTrue(session.isQuiescent(), "physical DONE must precede retirement");
            session.close();
            assertEquals(1, session.cleanupRuns(),
                    "source finally must run once during cancelled fourth pull");
            assertEquals(1, ((Number) session.cleanupBinding("cleanup_marker")).intValue());
            assertFalse(pendingSourceCall.isDone(),
                    "cancel cannot steal an externally-owned Future");
        }
    }

    @Test
    void invalidSourceRejectedBeforeHostSessionAndGuestEffects() {
        try (OresScheduler root = new OresScheduler(1)) {
            assertThrows(IllegalArgumentException.class, () ->
                    HostGeneratorSession.compile(root,
                            () -> OresScheduler.current() == root,
                            "async generator fnc numbers(): int { yield 1 + 2; return; }",
                            "numbers", Map.of()));
        }
    }

    @Test
    void wrongPhysicalGuestEntryPredicateFailsClosed() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorSession session = HostGeneratorSession.compile(
                    root, () -> false,
                    "async generator fnc numbers(): int { yield 1; return; }",
                    "numbers", Map.of());
            var pending = session.nextStep();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pending.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(pending.isCancelled(),
                    "denied guest entry must cancel the private queued host pull");
            session.close();
            assertTrue(session.isQuiescent(),
                    "rejected before producer start does not require fake physical DONE");
        }
    }
}

package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresHostFutureBridge;
import dev.oreslang.runtime.ChannelRuntime;
import dev.oreslang.runtime.OresFuture;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real two-thread guest-entry rendezvous with no sleeps in the admission
 * handshake. This checks the scoped Future handoff during guest execution,
 * not arbitrary Truffle thread-admission refinement.
 */
@Timeout(value = 40, unit = TimeUnit.SECONDS)
final class HostFutureCarrierRendezvousExecutionTest {
    private static final String PROGRAM = """
            define module gates as
              pub val Channel<int> start = Channel.new<int>(0);
              pub val Channel<int> cleanup = Channel.new<int>(0);
            end
            pub fnc exposeCleanup(): Channel<int> { return gates.cleanup; }
            pub async fnc work(): int {
              try {
                stdio.println("armed");
                const ignored = await (nb readch gates.start);
              } catch (cancelled) {
                stdio.println("INVALID-caught-cancellation");
              } finally {
                stdio.println("cleanup-entered");
                const cleaned = await (nb readch gates.cleanup);
                stdio.println(cleaned);
              }
              return 17;
            }
            """;

    private static final class BlockingGuestOutput extends ByteArrayOutputStream {
        final CountDownLatch guestEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        boolean parked;

        @Override
        public synchronized void write(int value) {
            super.write(value);
            parkAfterGuestEntry();
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            super.write(bytes, offset, length);
            parkAfterGuestEntry();
        }

        private void parkAfterGuestEntry() {
            if (parked || !toString(StandardCharsets.UTF_8).contains("armed")) return;
            parked = true;
            guestEntered.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("host never released guest-entry rendezvous");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted in guest rendezvous", interrupted);
            }
        }

        synchronized String snapshot() {
            return toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void guestCarrierCanBeParkedAfterEntryWhileHostOwnsFuture() throws Exception {
        BlockingGuestOutput output = new BlockingGuestOutput();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID, PROGRAM,
                    "two-thread-future-handoff.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            // Bind before starting any guest producer. The resulting Java
            // handle claims admission BEFORE every host Value.execute.
            OresHostFutureBridge.BoundSource bound =
                    OresHostFutureBridge.bindBeforeGuestStart(parsed);
            bound.executeCommand(OresEvalRootNode.INIT_ONLY_COMMAND);
            @SuppressWarnings("unchecked")
            ChannelRuntime.Channel<Integer> cleanup = (ChannelRuntime.Channel<Integer>)
                    bound.executeAndConvert(Value::asHostObject,
                            OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                            "exposeCleanup");
            OresFuture<Integer> future;
            try {
                future = bound.invokePublicFuture("work");
                assertTrue(output.guestEntered.await(5, TimeUnit.SECONDS),
                        "scheduler did not reach actual guest stdout");
                assertFalse(future.isDone(), "producer must remain active during rendezvous");
                // A real CONTROL carrier has physically entered Truffle.
                // This MUST fail on the host admission gate before a second
                // Java Value.execute can enter, not after Truffle rejects it.
                IllegalStateException denied = assertThrows(IllegalStateException.class,
                        () -> bound.executeAndConvert(Value::asHostObject,
                                OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                                "exposeCleanup"));
                assertTrue(denied.getMessage().contains("idle guest-entry"),
                        "expected pre-entry rejection, got: " + denied);
            } finally {
                output.release.countDown();
            }
            assertTrue(future.cancel(false));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output.snapshot().contains("cleanup-entered")
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(output.snapshot().contains("cleanup-entered"));
            cleanup.writeAsync(33).get(5, TimeUnit.SECONDS);
            assertThrows(CancellationException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertEquals("armed\ncleanup-entered\n33\n", output.snapshot());
            bound.close();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> bound.invokePublicFuture("work"));
        } finally {
            output.release.countDown();
        }
    }
}

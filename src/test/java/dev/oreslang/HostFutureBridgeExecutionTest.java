package dev.oreslang;

import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresHostFutureBridge;
import dev.oreslang.runtime.ChannelRuntime;
import dev.oreslang.runtime.OresFuture;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Host Future extraction must never re-enter Truffle after guest task dispatch. */
@Timeout(value = 80, unit = TimeUnit.SECONDS)
final class HostFutureBridgeExecutionTest {
    private static final String PROGRAM = """
            define module gates as
              pub val Channel<int> start = Channel.new<int>(0);
              pub val Channel<int> cleanup = Channel.new<int>(0);
            end

            pub fnc exposeCleanup(): Channel<int> {
              return gates.cleanup;
            }

            pub fnc ordinary(): int { return 17; }

            pub fnc syncSideEffect(): int {
              stdio.println("INVALID-sync-executed");
              return 17;
            }

            pub async fnc work(): int {
              try {
                stdio.println("armed");
                const ignored = await (nb readch gates.start);
              } catch (cancelled) {
                stdio.println("INVALID-caught-cancellation");
              } finally {
                stdio.println("cleanup-entered");
                const finished = await (nb readch gates.cleanup);
                stdio.println(finished);
              }
              return 17;
            }
            """;

    private static final class ConcurrentOutput extends ByteArrayOutputStream {
        @Override
        public synchronized void write(int value) {
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int count) {
            super.write(bytes, offset, count);
        }

        @Override
        public synchronized String toString() {
            return super.toString(StandardCharsets.UTF_8);
        }
    }

    private static void awaitOutput(ConcurrentOutput output, String text)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output.toString().contains(text) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(output.toString().contains(text),
                "guest did not reach " + text + ": " + output);
    }

    @Test
    void hostExtractsFutureWithoutPolyglotValueReentryDuringCleanup()
            throws Exception {
        for (int iteration = 0; iteration < 32; iteration++) {
            ConcurrentOutput output = new ConcurrentOutput();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(true).out(output).build()) {
                Source guest = Source.newBuilder(OresLanguage.ID, PROGRAM,
                        "host-bridged-future.ores")
                        .mimeType(OresLanguage.MIME_TYPE).build();
                Value parsed = context.parse(guest);
                parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
                @SuppressWarnings("unchecked")
                ChannelRuntime.Channel<Integer> cleanup =
                        (ChannelRuntime.Channel<Integer>) parsed.execute(
                                OresEvalRootNode.INVOKE_PUBLIC_COMMAND,
                                "exposeCleanup").asHostObject();

                // No call to Value.asHostObject follows the scheduler start.
                OresFuture<Integer> result =
                        OresHostFutureBridge.invokePublicFuture(parsed, "work");
                awaitOutput(output, "armed");
                assertFalse(result.isDone());
                assertTrue(result.cancel(false),
                        "cancellation was not admitted at repetition " + iteration);
                awaitOutput(output, "cleanup-entered");
                assertFalse(result.isDone(),
                        "pending guest cleanup must keep the Future nonterminal");
                cleanup.writeAsync(33).get(5, TimeUnit.SECONDS);
                assertThrows(CancellationException.class,
                        () -> result.get(5, TimeUnit.SECONDS));
                assertEquals("armed\ncleanup-entered\n33\n", output.toString());
            }
        }
    }

    @Test
    void forgedBridgedCommandCannotStartAnOrphanedGuestTask()
            throws Exception {
        ConcurrentOutput output = new ConcurrentOutput();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID,
                    PROGRAM, "forged-host-bridge.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            PolyglotException rejection = assertThrows(PolyglotException.class,
                    () -> parsed.execute(
                            OresEvalRootNode.INVOKE_PUBLIC_FUTURE_BRIDGE_COMMAND, "work"));
            assertTrue(rejection.getMessage().contains(
                    "invalid scoped host Future handoff command"),
                    "missing nonce must fail at admission, before dispatching any guest task");
            assertEquals("", output.toString(),
                    "unauthenticated control command must be rejected before scheduling");
        }
    }

    @Test
    void malformedAndGuessedNonceCannotDispatchOrFallThroughToMain()
            throws Exception {
        ConcurrentOutput output = new ConcurrentOutput();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID,
                    PROGRAM, "invalid-host-bridge.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);

            String command = OresEvalRootNode.INVOKE_PUBLIC_FUTURE_BRIDGE_COMMAND;
            PolyglotException missing = assertThrows(PolyglotException.class,
                    () -> parsed.execute(command));
            assertTrue(missing.getMessage().contains(
                    "invalid scoped host Future handoff command"));
            PolyglotException malformed = assertThrows(PolyglotException.class,
                    () -> parsed.execute(command, "work", 123));
            assertTrue(malformed.getMessage().contains(
                    "invalid scoped host Future handoff command"));
            PolyglotException guessed = assertThrows(PolyglotException.class,
                    () -> parsed.execute(command, "work", "stale-or-guessed-token"));
            assertTrue(guessed.getMessage().contains(
                    "matching scoped invocation"));
            assertEquals("", output.toString(),
                    "malformed or guessed bridge must not run guest work or main");
        }
    }

    @Test
    void adversarialPolicyRefusesUnpumpedHostFutureBeforeScheduling()
            throws Exception {
        String source = "pub async fnc work(): int { return 17; }";
        // This verifies Oreslang's effective adversarial admission policy;
        // it does not substitute for a full Graal isolate sandbox test.
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true)
                .arguments(OresLanguage.ID,
                        new String[]{"--ores-capabilities=", "--ores-adversarial=true"})
                .build()) {
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID,
                    source, "adversarial-bridge-rejection.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            PolyglotException rejection = assertThrows(PolyglotException.class,
                    () -> OresHostFutureBridge.invokePublicFuture(parsed, "work"));
            assertTrue(rejection.getMessage().contains(
                    "scoped host Future bridge is unavailable in adversarial contexts"),
                    "host Future launch cannot orphan queued guest turns");
        }
    }

    @Test
    void synchronousGuestSideEffectsMustNotRunBeforeAsyncRejection()
            throws Exception {
        ConcurrentOutput output = new ConcurrentOutput();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).out(output).build()) {
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID,
                    PROGRAM, "sync-side-effect-rejection.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            PolyglotException rejection = assertThrows(PolyglotException.class,
                    () -> OresHostFutureBridge.invokePublicFuture(
                            parsed, "syncSideEffect"));
            assertTrue(rejection.getMessage().contains(
                    "host Future handoff requires an async public function"));
            assertEquals("", output.toString(),
                    "rejected sync function must not execute before the admission check");
        }
    }

    @Test
    void rejectingSyncFunctionDoesNotLeaveStaleHostCapture()
            throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(true).build()) {
            Value parsed = context.parse(Source.newBuilder(OresLanguage.ID,
                    PROGRAM, "host-bridged-sync.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            parsed.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            PolyglotException first = assertThrows(PolyglotException.class,
                    () -> OresHostFutureBridge.invokePublicFuture(parsed, "ordinary"));
            assertTrue(first.getMessage().contains(
                    "host Future handoff requires an async public function"));
            PolyglotException second = assertThrows(PolyglotException.class,
                    () -> OresHostFutureBridge.invokePublicFuture(parsed, "ordinary"),
                    "failed invocation must not retain a prior host capture scope");
            assertTrue(second.getMessage().contains(
                    "host Future handoff requires an async public function"));
        }
    }
}

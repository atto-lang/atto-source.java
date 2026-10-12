package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution-level regression: ticket allocation must be atomic with
 * transitioning a source receive to the suspended mailbox state.
 * A failed issuance must never strand the mailbox or borrow the next receive.
 */
@Timeout(15)
final class ActorSuspensionTicketAtomicityTest {

    @Test
    void exhaustedCompletionTicketLeavesOriginalReceiveRunnableAndNextMailAdmissible()
            throws Exception {
        CountDownLatch overflowChecked = new CountDownLatch(1);
        CountDownLatch secondMailReceived = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 1, 16))) {
            var image = store.publish("ticket-exhaustion.ores", Parser.parse("""
                    define actor Worker as
                        receive(ActorMail<String> mail): void { return; }
                    end
                    pub routine main(): void { return; }
                    """));
            var executor = new ActorRuntime.ActorCodeExecutor() {
                @Override
                public SharedCodeImageStore.CodeImage codeImage() { return image; }

                @Override
                public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return context.self()::id;
                }

                @Override
                public void receiveActor(String type,
                        ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    if ("overflow".equals(mail.value())) {
                        try {
                            Object cell = actorCell(runtime, context.self().id());
                            Field next = cell.getClass().getDeclaredField("nextSourceTaskId");
                            next.setAccessible(true);
                            next.setLong(cell, Long.MAX_VALUE);
                            assertThrows(ArithmeticException.class,
                                    () -> runtime.suspendCurrentUserMailboxUntil(
                                            new OresFuture<>()),
                                    "issuing an exhausted terminal ticket must fail closed");

                            Field suspended = cell.getClass()
                                    .getDeclaredField("sourceReceiveSuspended");
                            suspended.setAccessible(true);
                            assertFalse(suspended.getBoolean(cell),
                                    "ticket failure must not strand the source receive");
                        } catch (Throwable unexpected) {
                            failure.set(unexpected);
                        } finally {
                            overflowChecked.countDown();
                        }
                    } else if ("next".equals(mail.value())) {
                        secondMailReceived.countDown();
                        runtime.endCurrentActorWithCleanup(() -> { });
                    }
                }
            };

            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executor, "Worker");
            try {
                actor.ready().get(3, TimeUnit.SECONDS);
                actor.send("overflow");
                assertTrue(overflowChecked.await(3, TimeUnit.SECONDS),
                        "source receive did not exercise issuance failure");
                if (failure.get() != null) fail("ticket atomicity assertion failed", failure.get());
                actor.send("next");
                assertTrue(secondMailReceived.await(3, TimeUnit.SECONDS),
                        "failed ticket issuance stranded the actor mailbox");
                actor.done().get(3, TimeUnit.SECONDS);
            } finally {
                actor.cancel();
            }
        }
    }

    private static Object actorCell(ActorRuntime runtime, Object actorId)
            throws ReflectiveOperationException {
        Field actors = ActorRuntime.class.getDeclaredField("actors");
        actors.setAccessible(true);
        Object cell = ((Map<?, ?>) actors.get(runtime)).get(actorId);
        assertNotNull(cell, "current actor cell must be registered");
        return cell;
    }
}

package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authenticated Java runtime substrate for future compiler-generated
 * self.handle_state: these are REAL serialized actor mailbox turns.
 * They are not a claim of source-language lowering.
 */
@Timeout(20)
final class ActorStateLoanRuntimeTest {
    @Test
    void ownerBoundLoansRejectEscapesNestedWritesAndFailedTransactions() throws Exception {
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 1, 128))) {
            var image = images.publish("state-loans.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            AtomicReference<ActorRuntime.ActorStateMap> leaked = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorStateMap.ReadLoan> expired = new AtomicReference<>();
            AtomicLong value = new AtomicLong(-1);
            AtomicLong incremented = new AtomicLong(-1);
            AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
            AtomicReference<String> phase = new AtomicReference<>("not started");
            CountDownLatch completed = new CountDownLatch(2);
            var executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    var state = runtime.createCurrentActorState(Map.of("count", 7L));
                    leaked.set(state);
                    return state;
                }

                @Override public void receiveActor(
                        String type, ActorRuntime.ActorOwnedGuestState instance,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    var state = (ActorRuntime.ActorStateMap) instance;
                    phase.set("received " + mail.value());
                    try {
                    if (mail.value().equals("work")) {
                        phase.set("work: read");
                        value.set(state.read(s -> {
                            expired.set(s);
                            assertEquals(7L, s.get("count"));
                            assertThrows(IllegalStateException.class,
                                    () -> state.read(nested -> nested.get("count")));
                            assertThrows(IllegalStateException.class,
                                    () -> state.write(nested -> nested.get("count")));
                            return s.get("count");
                        }));
                        phase.set("work: expiry");
                        assertThrows(IllegalStateException.class, () -> expired.get().get("count"));
                        phase.set("work: bad-key");
                        assertThrows(IllegalArgumentException.class,
                                () -> state.write(s -> { s.set("unknown", 4); return 0; }));
                        assertEquals(0L, state.read(s -> {
                            assertThrows(IllegalStateException.class,
                                    () -> state.write(w -> 0));
                            return 0;
                        }));
                        assertThrows(IllegalArgumentException.class,
                                () -> state.write(s -> {
                                    s.set("count", 900);
                                    throw new IllegalArgumentException("rollback");
                                }));
                        phase.set("work: rollback completed");
                        assertEquals(7L, state.read(s -> s.get("count")));
                        incremented.set(state.write(s -> {
                            s.set("count", s.get("count") + 1);
                            return s.get("count");
                        }));
                        assertEquals(8L, state.read(s -> s.get("count")));
                        phase.set("work: completed");
                        completed.countDown();
                    } else if (mail.value().equals("verify")) {
                        assertEquals(8L, state.read(s -> s.get("count")));
                        completed.countDown();
                    } else if (mail.value().equals("stop")) {
                        runtime.endCurrentActor();
                    }
                    } catch (Throwable unexpected) {
                        handlerFailure.compareAndSet(null, unexpected);
                        completed.countDown();
                        completed.countDown();
                        throw new IllegalStateException("actor state test " + phase.get(), unexpected);
                    }
                }
            };
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, executor, "StateActor");
            actor.ready().get(3, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> leaked.get().read(s -> s.get("count")));
            assertThrows(IllegalStateException.class, () -> leaked.get().write(s -> s.get("count")));
            assertThrows(IllegalStateException.class,
                    () -> runtime.createCurrentActorState(Map.of("count", 0L)));
            actor.send("work");
            actor.send("verify");
            assertTrue(completed.await(5, TimeUnit.SECONDS),
                    () -> "actor state handler stalled at " + phase.get() + " / " + handlerFailure.get());
            assertNull(handlerFailure.get(), () -> "actor state failure at " + phase.get());
            assertEquals(7L, value.get());
            assertEquals(8L, incremented.get());
            actor.send("stop");
            actor.done().get(3, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> expired.get().get("count"));
            assertThrows(IllegalStateException.class, () -> leaked.get().read(s -> s.get("count")));
        }
    }

    @Test
    void actorStateDoesNotExposeStateMapsOrForgedKeysToHost() {
        try (var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            assertThrows(IllegalStateException.class,
                    () -> runtime.createCurrentActorState(Map.of("balance", 0L)));
        }
    }
}

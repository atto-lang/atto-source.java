package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real actor lifecycle proof for the runtime-only state-retirement permit.
 * The permit cannot be constructed by guest code, cannot retire a foreign
 * owner, and expires when the actor's terminal callback returns.
 */
@Timeout(20)
final class ActorStateRetirementPermitTest {
    @Test void runtimeFinalizationRetiresOnlyItsOwnerAndRevokesThePermit() throws Exception {
        AtomicReference<ActorRuntime.StateRetirementPermit> issued = new AtomicReference<>();
        AtomicReference<SealedActorState> owned = new AtomicReference<>();
        AtomicInteger retired = new AtomicInteger();
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
            var image = store.publish("retirement-permit.atto",
                    Parser.parse("pub routine main(): void { return; }"));
            var executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() {
                    return image;
                }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    var ownerId = context.self().id();
                    var state = new SealedActorState(ownerId,
                            Map.of("count", "int"), Map.of("count", 1L));
                    owned.set(state);
                    return new ActorRuntime.ActorOwnedGuestState() {
                        @Override public ActorRuntime.ActorId ownerActorId() {
                            return ownerId;
                        }
                        @Override public void retireFromRuntime(
                                ActorRuntime.StateRetirementPermit permit) {
                            var foreign = new SealedActorState(
                                    ActorRuntime.ActorId.create(),
                                    Map.of("count", "int"), Map.of("count", 2L));
                            assertThrows(SecurityException.class,
                                    () -> permit.retire(foreign),
                                    "owner mismatch must fail without erasing foreign state");
                            permit.retire(state);
                            issued.set(permit);
                            retired.incrementAndGet();
                        }
                    };
                }
                @Override public void receiveActor(
                        String type, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) { }
            };
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, executor, "RetirementProbe");
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.stop();
            actor.done().get(5, TimeUnit.SECONDS);
            assertTrue(actor.failure().isEmpty(), "runtime finalization must not fail");
            assertEquals(1, retired.get(), "state must retire exactly once");
            assertNotNull(owned.get());
            var permit = issued.get();
            assertNotNull(permit);
            assertThrows(SecurityException.class,
                    () -> permit.retire(owned.get()),
                    "captured permit must be revoked after the callback");
        }
        assertTrue(Modifier.isPrivate(
                ActorRuntime.StateRetirementPermit.class
                        .getDeclaredConstructors()[0].getModifiers()),
                "untrusted source must not be able to mint retirement authority");
    }
}

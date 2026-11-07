package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic host-side mutation adversaries: a malicious sender collection
 * returns an authorized value during preflight and a forbidden value ONLY when
 * ActorRuntime physically copies its payload. The detached result is what must
 * be admitted, not the mutable sender's earlier view.
 */
@Timeout(15)
final class ActorSymbolSnapshotAdmissionTest {
    private static boolean actorRuntimeCalling(String method) {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getClassName().equals(ActorRuntime.class.getName())
                        && frame.getMethodName().equals(method)));
    }

    @Test void untrustedMailboxRejectsHiddenSymbolIntroducedDuringCopy() {
        try (var runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Map<Object, Object>> ref =
                    runtime.spawnUntrusted(context -> (message, turn) ->
                            turn.runtime().endCurrentActor());
            ref.ready().join();

            Map<Object, Object> changing = new AbstractMap<>() {
                @Override public Set<Entry<Object, Object>> entrySet() {
                    OresSymbol key = OresSymbol.process(
                            actorRuntimeCalling("isolateCopy") ? "tenant/private" : "ready");
                    return Set.of(Map.entry(key, 1L));
                }
            };

            SecurityException rejection = assertThrows(SecurityException.class,
                    () -> ref.send(changing));
            assertTrue(rejection.getMessage().contains("Symbol"),
                    rejection.getMessage());
            // Failed pre-admission must refund its mailbox reservation.
            ref.send(Map.of(OresSymbol.process("ready"), 1L));
            ref.done().join();
        }
    }

    @Test void sharedActorOutputRejectsLiveCapabilityIntroducedDuringFreeze() {
        try (var runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(context ->
                    (message, turn) -> {
                        Map<String, Object> changing = new AbstractMap<>() {
                            @Override public Set<Entry<String, Object>> entrySet() {
                                Object value = actorRuntimeCalling("freeze")
                                        ? turn.self() : "safe";
                                return Set.of(Map.entry("payload", value));
                            }
                        };
                        turn.runtime().emitCurrentActorOutput(changing);
                    });
            ref.ready().join();
            ref.send("go");
            CompletionException rejection = assertThrows(CompletionException.class,
                    () -> ref.done().join());
            assertInstanceOf(IllegalArgumentException.class, rejection.getCause());
            assertTrue(rejection.getCause().getMessage().contains("data values only"),
                    rejection.getCause().getMessage());
        }
    }
}

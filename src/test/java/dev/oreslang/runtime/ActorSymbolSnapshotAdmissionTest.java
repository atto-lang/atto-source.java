package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import dev.oreslang.parser.Parser;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test void sharedActorCannotSpoofAnotherLiteralImageTrustDomain() {
        var program = Parser.parse("pub routine main(): void { return; }");
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime()) {
            var image = images.publish("trust-domain.atto", program,
                    java.util.List.of(OresSymbol.process("ready")));
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(context ->
                    (message, turn) -> {
                        assertTrue(ActorRuntime.inActorExecution());
                        assertEquals(ActorRuntime.ActorKind.SHARED,
                                ActorRuntime.currentActorKind());
                        assertEquals(OresSymbol.process("ready"),
                                image.requireGuestStaticLiteral(
                                        OresSymbol.process("ready"),
                                        ActorRuntime.ActorKind.SHARED));
                        assertThrows(SecurityException.class,
                                () -> image.literalForActor(
                                        OresSymbol.process("ready"),
                                        ActorRuntime.ActorKind.PRIVATE));
                        assertThrows(SecurityException.class,
                                () -> image.requireGuestStaticLiteral(
                                        OresSymbol.process("ready"), null));
                        turn.runtime().endCurrentActor();
                    });
            actor.ready().join();
            actor.send("probe");
            actor.done().join();
        }
    }

    @Test void actualPrivateActorMailboxReceivesDetachedSymbolsAcrossNestedGraph() {
        OresSymbol processKey = OresSymbol.process("ready");
        OresSymbol stableValue = OresSymbol.stable(
                "550e8400-e29b-41d4-a716-446655440000");
        Map<Object, Object> original = new LinkedHashMap<>();
        original.put(processKey, new Object[]{stableValue, processKey});
        AtomicReference<Map<Object, Object>> observed = new AtomicReference<>();

        try (var runtime = new ActorRuntime()) {
            // Trusted host-only capture is used to inspect the received graph,
            // NOT as a compiler/guest source actor isolation proof.
            ActorRuntime.ActorRef<Map<Object, Object>> ref =
                    runtime.spawnPrivateTrusted(context -> (message, turn) -> {
                        assertEquals(ActorRuntime.ActorKind.PRIVATE, context.kind());
                        observed.set(message);
                        turn.runtime().endCurrentActor();
                    });
            ref.ready().join();
            ref.send(original);
            ref.done().join();
        }

        Map<Object, Object> snapshot = observed.get();
        assertNotNull(snapshot, "the PRIVATE actor must run a real handler");
        assertNotSame(original, snapshot);
        OresSymbol copiedKey = (OresSymbol) snapshot.keySet().iterator().next();
        assertEquals(processKey, copiedKey);
        assertNotSame(processKey, copiedKey,
                "private mailbox keys must not borrow sender Symbols");
        assertInstanceOf(List.class, snapshot.get(processKey));
        List<?> copiedArray = (List<?>) snapshot.get(processKey);
        assertEquals(2, copiedArray.size());
        assertEquals(stableValue, copiedArray.get(0));
        assertNotSame(stableValue, copiedArray.get(0),
                "STABLE Symbol payload must be detached");
        assertEquals(processKey, copiedArray.get(1));
        assertNotSame(processKey, copiedArray.get(1));
        assertNotSame(copiedKey, copiedArray.get(1),
                "each occurrence of a Symbol is copied independently");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("smuggled", "mutation"));
    }

    @Test void actualUntrustedMailboxRejectsNestedStableAndHiddenSymbolsBeforeQueueing() {
        try (var runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Map<Object, Object>> ref =
                    runtime.spawnUntrusted(context -> (message, turn) -> {
                        assertEquals(ActorRuntime.ActorKind.UNTRUSTED, turn.kind());
                        turn.runtime().endCurrentActor();
                    });
            ref.ready().join();

            // A visible outer key never authorizes a nested STABLE value.
            assertThrows(SecurityException.class, () -> ref.send(Map.of(
                    OresSymbol.process("ready"),
                    List.of(OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000")))));
            // Nor may an internal PROCESS symbol appear nested in a value.
            assertThrows(SecurityException.class, () -> ref.send(Map.of(
                    OresSymbol.process("ready"),
                    List.of(OresSymbol.process("tenant/private")))));

            // Failed admissions must not reserve or poison the mailbox;
            // a public-core-only graph should still reach the actor.
            ref.send(Map.of(OresSymbol.process("ready"),
                    List.of(OresSymbol.process("error"))));
            ref.done().join();
        }
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

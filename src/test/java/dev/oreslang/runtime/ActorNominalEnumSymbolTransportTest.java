package dev.oreslang.runtime;

import dev.oreslang.nodes.OresEvalRootNode.NominalEnumValue;
import dev.oreslang.parser.Parser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport-level proofs for compiler-owned nullary enum values.
 * Reflection is used only to construct test fixtures; guest source cannot
 * reflect into or manufacture this private constructor. Full .atto guest
 * actor/enum integration is a separate compiler acceptance gate.
 */
@Timeout(25)
final class ActorNominalEnumSymbolTransportTest {
    private static NominalEnumValue fixture(String unit, String variant, Object payload)
            throws Exception {
        Constructor<NominalEnumValue> constructor =
                NominalEnumValue.class.getDeclaredConstructor(
                        String.class, String.class, String.class, int.class, Object.class);
        assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                "host callers must not mint compiler enum identities");
        constructor.setAccessible(true);
        return constructor.newInstance(unit, "Light", variant, 1, payload);
    }

    @Test
    void nullarySymbolsAreImmutableNominalValuesNotIntegersOrStrings() throws Exception {
        NominalEnumValue red = fixture("module-A.atto", "Red", null);
        NominalEnumValue equivalent = fixture("module-A.atto", "Red", null);
        NominalEnumValue foreign = fixture("module-B.atto", "Red", null);
        assertEquals(red, equivalent);
        assertEquals(red.hashCode(), equivalent.hashCode());
        assertNotEquals(red, foreign, "defining source-unit identity is part of the enum type");
        assertNotEquals(red, 1);
        assertNotEquals(red, "Red");
        assertEquals("Light.Red", red.toString());
        assertSame(red, ActorRuntime.freeze(red),
                "nullary symbol must be transported directly, never serialized or cloned");
        List<?> frozen = assertInstanceOf(
                List.class, ActorRuntime.freeze(new Object[]{red}));
        assertSame(red, frozen.getFirst(), "copying a container does not copy its symbol");
    }

    @Test
    void payloadEnumAndHostImpersonatorCannotBypassActorTransport() throws Exception {
        NominalEnumValue payload = fixture("module-A.atto", "Red", new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(payload),
                "payload-bearing variants need explicit ownership checking");
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(List.of(payload)),
                "wrapping an owned payload in a collection must not launder it");
        record FakeSymbol(String ownerUnitId, String enumName, String variant, int tag) { }
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(new FakeSymbol("module-A.atto", "Light", "Red", 1)),
                "structurally similar host values are not compiler-owned enum symbols");
    }

    @Test
    void mailboxDeliversSameNullarySymbolAcrossPrivateSharedAndUntrustedActors() throws Exception {
        NominalEnumValue red = fixture("module-A.atto", "Red", null);
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            CountDownLatch received = new CountDownLatch(2);
            List<Object> inbox = Collections.synchronizedList(new ArrayList<>());
            try (var store = new SharedCodeImageStore();
                 var runtime = new ActorRuntime(
                         IsolatePolicy.developer(),
                         new ActorRuntime.DispatcherConfig(2, 2, 1, 64))) {
                var image = store.publish(
                        "actor-symbol-transport.atto",
                        Parser.parse("pub routine main(): void { return; }"));
                var executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() {
                        return image;
                    }
                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        return context.self()::id;
                    }
                    @Override public void receiveActor(
                            String type, ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) {
                        inbox.add(mail.value());
                        received.countDown();
                    }
                };
                var receiver = runtime.spawnCodeActor(kind, executor, "LightReceiver");
                receiver.ready().get(5, TimeUnit.SECONDS);
                assertTrue(runtime.mailboxUsesChannelTransport(receiver));
                receiver.send(red);
                Object[] nested = new Object[]{red};
                receiver.send(nested);
                assertTrue(received.await(5, TimeUnit.SECONDS), kind + " mailbox not delivered");
                assertSame(red, inbox.get(0), kind + " nullary symbol was copied or wrapped");
                List<?> copied = assertInstanceOf(List.class, inbox.get(1));
                assertNotSame(nested, copied, kind + " must isolate mutable containers");
                assertSame(red, copied.getFirst(), kind + " nested symbol changed identity");
                receiver.cancel();
            }
        }
    }
}

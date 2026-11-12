package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.NamespaceActorIngressV2;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real AttoRMQ exact critical source blobs -> typed MIR -> Truffle native
 * actor-owned state. Only the test's external receive body is rewritten into
 * a strict ActorMail<int> dispatch fixture. The production broker ADT receive
 * bodies remain unchanged and fail closed.
 */
final class AttoRmqMultimethodExecutionV2Test {
    private static String original(String name) throws IOException {
        try (var stream = AttoRmqMultimethodExecutionV2Test.class
                .getResourceAsStream("/atto-rmq-v2/" + name + ".atto")) {
            if (stream == null) throw new IOException("missing exact broker actor fixture: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String routedSource(String source, String actor, String branches) {
        String prefix = "external mtd (" + actor + " c) receive(";
        int start = source.indexOf(prefix);
        assertTrue(start >= 0, "must patch the original actor ingress, not critical methods");
        int opening = source.indexOf('{', start);
        int closing = source.indexOf("\n}", opening);
        assertTrue(opening > start && closing > opening,
                "broker ingress fixture requires a simple bounded no-op body");
        String originalReceive = source.substring(start, closing + 2);
        assertTrue(originalReceive.contains("return;"), "source receive must remain fail closed");
        String next = """
                external mtd (%s c) receive(ActorMail<int> mail): void {
                %s
                    return;
                }
                """.formatted(actor, branches);
        String changed = source.substring(0, start) + next + source.substring(closing + 2);
        assertEquals(source.substring(closing + 2), changed.substring(start + next.length()),
                "critical methods must remain byte-identical after replacement");
        return changed;
    }

    private static final String CONNECTION_ROUTES = """
                    if mail.value eq 1 then
                        c.send(c.reserve_frame_credit(1024));
                    elif mail.value eq 2 then
                        c.send(c.finish_frame_credit(1024, 0));
                    elif mail.value eq 3 then
                        c.set_closing();
                        c.send(77);
                    elif mail.value eq 4 then
                        c.send(c.reserve_frame_credit(1024));
                    fi
            """;

    private static final String CHANNEL_ROUTES = """
                    if mail.value eq 1 then
                        c.send(c.reserve_delivery_tag());
                    elif mail.value eq 2 then
                        c.send(c.reserve_publish_seq());
                    elif mail.value eq 3 then
                        c.close_channel();
                        c.send(77);
                    elif mail.value eq 4 then
                        c.send(c.reserve_delivery_tag());
                    fi
            """;

    private static NamespaceActorIngressV2.Plan compile(
            SharedCodeImageStore store, String unit, String source, String actor) {
        var image = store.publish(unit, Parser.parse(source));
        return OresCompiler.compileNamespaceActorIngressV2(image, "atto-rmq@exact", actor);
    }

    private static Object sendAndRead(ActorRuntime.ActorRef<Object> ref, long opcode)
            throws Exception {
        ref.sendAsync(opcode).get(15, TimeUnit.SECONDS);
        var step = ref.outputs().next().get(15, TimeUnit.SECONDS);
        assertFalse(step.done());
        return step.value().value();
    }

    @Test
    void exactBrokerActorsCompileWithThreeCriticalMethodsAndNoActivation()
            throws Exception {
        for (String name : new String[]{"connection", "channel"}) {
            String original = original(name);
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(original)))
                    .getMessage().contains("LOWERING_UNSUPPORTED"));
            try (var store = new SharedCodeImageStore();
                 var runtime = new ActorRuntime(IsolatePolicy.developer())) {
                String actor = name.equals("connection") ? "ConnectionActor" : "ChannelActor";
                var plan = compile(store, "src/actors/" + name + ".atto", original, actor);
                assertEquals(3, plan.criticalMethodCount());
                assertTrue(plan.failClosedReceive(), "original broker receive must remain no-op");
                var ref = plan.spawn(runtime, IsolatePolicy.developer());
                ref.ready().get(15, TimeUnit.SECONDS);
                ref.sendAsync(999L).get(15, TimeUnit.SECONDS);
                ref.stop();
                assertTrue(ref.awaitTermination(15, TimeUnit.SECONDS));
                assertTrue(ref.failure().isEmpty());
            }
        }
    }

    @Test
    void actualConnectionCriticalMethodsExecuteInsideOneActor() throws Exception {
        String source = routedSource(original("connection"), "ConnectionActor", CONNECTION_ROUTES);
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/actors/connection.atto", source, "ConnectionActor");
            assertEquals(3, plan.criticalMethodCount());
            assertFalse(plan.failClosedReceive());
            var actor = plan.spawn(runtime, IsolatePolicy.developer());
            actor.ready().get(15, TimeUnit.SECONDS);
            for (int i = 0; i < 1_000; i++) {
                assertEquals(false, sendAndRead(actor, 1));
                assertEquals(false, sendAndRead(actor, 2));
            }
            assertEquals(77L, sendAndRead(actor, 3));
            assertEquals(false, sendAndRead(actor, 4));
            actor.stop();
            assertTrue(actor.awaitTermination(15, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void actualChannelCriticalMethodsExecuteInsideOneActor() throws Exception {
        String source = routedSource(original("channel"), "ChannelActor", CHANNEL_ROUTES);
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/actors/channel.atto", source, "ChannelActor");
            assertEquals(3, plan.criticalMethodCount());
            assertFalse(plan.failClosedReceive());
            var actor = plan.spawn(runtime, IsolatePolicy.developer());
            actor.ready().get(15, TimeUnit.SECONDS);
            for (int i = 0; i < 1_000; i++) {
                assertEquals(0L, sendAndRead(actor, 1));
                assertEquals(0L, sendAndRead(actor, 2));
            }
            assertEquals(77L, sendAndRead(actor, 3));
            assertEquals(0L, sendAndRead(actor, 4));
            actor.stop();
            assertTrue(actor.awaitTermination(15, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void checkedSourceRoutesRejectForgedOrUnverifiedDispatch() throws Exception {
        var base = routedSource(original("connection"), "ConnectionActor", CONNECTION_ROUTES);
        var variants = new String[]{
                base.replace("c.send(c.reserve_frame_credit(1024))",
                        "c.send(c.reserve_frame_credit(true))"),
                base.replace("c.send(c.reserve_frame_credit(1024))",
                        "c.send(c.reserve_frame_credit(mail.sender))"),
                base.replace("c.send(c.reserve_frame_credit(1024))",
                        "c.send(super.reserve_frame_credit(1024))"),
                base.replace("c.send(c.reserve_frame_credit(1024))",
                        "c.send(c.reserve_frame_credit)"),
                base.replace("c.send(c.reserve_frame_credit(1024))",
                        "c.send(c.unknown(1024))"),
                base.replace("c.send(c.finish_frame_credit(1024, 0))",
                        "c.send(c.finish_frame_credit(1024))"),
                base.replace("c.send(c.finish_frame_credit(1024, 0))",
                        "c.send(c.finish_frame_credit(mail.value, false))"),
                base.replace("c.set_closing()", "c.set_closing(1)"),
                base.replace("mail.value eq 2", "mail.value eq 1"),
                base.replace("mail.value eq 2", "mail.sender eq 2"),
                base.replace("c.set_closing();", "c.set_closing(); c.set_closing();"),
                base.replace("ActorMail<int> mail", "ActorMail<ConnectionMail> mail"),
                base.replace("external mtd (ConnectionActor c) receive",
                        "pub mtd (ConnectionActor c) receive")
        };
        for (String mutation : variants) {
            assertNotEquals(base, mutation, "negative mutation must actually change source");
            try (var store = new SharedCodeImageStore()) {
                assertThrows(RuntimeException.class,
                        () -> compile(store, "src/actors/connection.atto", mutation,
                                "ConnectionActor"),
                        "unverified ingress would become a guest state capability");
            }
        }
    }

    @Test
    void stopAndNewIncarnationDoNotShareActorArena() throws Exception {
        String source = routedSource(original("channel"), "ChannelActor", CHANNEL_ROUTES);
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/actors/channel.atto", source, "ChannelActor");
            var old = plan.spawn(runtime, IsolatePolicy.developer());
            old.ready().get(15, TimeUnit.SECONDS);
            assertEquals(0L, sendAndRead(old, 1));
            old.stop();
            assertTrue(old.awaitTermination(15, TimeUnit.SECONDS));
            var next = plan.spawn(runtime, IsolatePolicy.developer());
            next.ready().get(15, TimeUnit.SECONDS);
            assertEquals(0L, sendAndRead(next, 2));
            assertThrows(RuntimeException.class, () -> old.send(1L));
            next.stop();
            assertTrue(next.awaitTermination(15, TimeUnit.SECONDS));
            assertTrue(next.failure().isEmpty());
            assertEquals(1, store.imageCount());
        }
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.NamespaceActorIngressV3;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Original AttoRMQ typed ADT actor source, compiled to Truffle critical
 * methods and executed through real runtime-only external match ingress.
 * Not official AMQP transport, native AOT or runtime-issued AuthGrant.
 */
final class AttoRmqTypedAdtIngressV3Test {
    private static String fixture(String actor) throws Exception {
        try (var stream = AttoRmqTypedAdtIngressV3Test.class
                .getResourceAsStream("/atto-rmq-v3/" + actor + ".atto")) {
            if (stream == null) throw new IllegalStateException("missing exact broker source");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static NamespaceActorIngressV3.Plan compile(
            SharedCodeImageStore store, String file, String source, String actor) {
        var ast = Parser.parse(source);
        var image = store.publish(file, ast);
        return OresCompiler.compileNamespaceActorIngressV3(
                image, "atto-rmq@typed-v3", actor);
    }

    private static Object roundtrip(ActorRuntime.ActorRef<Object> actor,
                                    List<Object> mail) throws Exception {
        actor.sendAsync(mail).get(20, TimeUnit.SECONDS);
        var next = actor.outputs().next().get(20, TimeUnit.SECONDS);
        assertFalse(next.done());
        return next.value().value();
    }

    @Test
    void exactConnectionMailTypedPayloadRoutesTenThousandMessages() throws Exception {
        String source = fixture("connection");
        Ast.Program ast = Parser.parse(source);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(ast)).getMessage().contains("LOWERING_UNSUPPORTED"));
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/actors/connection.atto",
                    source, "ConnectionActor");
            assertEquals(3, plan.variantCount());
            assertEquals(3, plan.criticalMethodCount());
            var actor = plan.spawn(runtime, IsolatePolicy.developer());
            actor.ready().get(20, TimeUnit.SECONDS);
            for (int i = 0; i < 5_000; i++) {
                assertEquals(Boolean.FALSE,
                        roundtrip(actor, plan.message("FrameCreditRequested", 1024L)));
                assertEquals(Boolean.FALSE,
                        roundtrip(actor, plan.message("FrameCreditCompleted", 1024L, 0L)));
            }
            actor.sendAsync(plan.message("CloseRequested")).get(20, TimeUnit.SECONDS);
            assertEquals(Boolean.FALSE,
                    roundtrip(actor, plan.message("FrameCreditRequested", 1L)));
            actor.stop();
            assertTrue(actor.awaitTermination(20, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void exactChannelMailSymbolVariantsDispatchTenThousandMessages() throws Exception {
        String source = fixture("channel");
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/actors/channel.atto", source, "ChannelActor");
            assertEquals(3, plan.variantCount());
            assertEquals(3, plan.criticalMethodCount());
            var actor = plan.spawn(runtime, IsolatePolicy.developer());
            actor.ready().get(20, TimeUnit.SECONDS);
            for (int i = 0; i < 5_000; i++) {
                assertEquals(0L,
                        roundtrip(actor, plan.message("DeliveryTagRequested")));
                assertEquals(0L,
                        roundtrip(actor, plan.message("PublisherSequenceRequested")));
            }
            actor.sendAsync(plan.message("CloseRequested")).get(20, TimeUnit.SECONDS);
            assertEquals(0L,
                    roundtrip(actor, plan.message("DeliveryTagRequested")));
            actor.stop();
            assertTrue(actor.awaitTermination(20, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void twoActorsNeverShareAdtMailboxOrCriticalState() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/actors/channel.atto",
                    fixture("channel"), "ChannelActor");
            var a = plan.spawn(runtime, IsolatePolicy.developer());
            var b = plan.spawn(runtime, IsolatePolicy.developer());
            a.ready().get(20, TimeUnit.SECONDS);
            b.ready().get(20, TimeUnit.SECONDS);
            a.sendAsync(plan.message("CloseRequested")).get(20, TimeUnit.SECONDS);
            assertEquals(0L, roundtrip(b, plan.message("DeliveryTagRequested")));
            assertEquals(0L, roundtrip(a, plan.message("DeliveryTagRequested")));
            a.stop();
            b.stop();
            assertTrue(a.awaitTermination(20, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(20, TimeUnit.SECONDS));
            assertTrue(a.failure().isEmpty());
            assertTrue(b.failure().isEmpty());
        }
    }

    @Test
    void noHostConstructedWrongPayloadOrCrossActorNominalAdmission() throws Exception {
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var connection = compile(store, "src/actors/connection.atto",
                    fixture("connection"), "ConnectionActor");
            var channel = compile(store, "src/actors/channel.atto",
                    fixture("channel"), "ChannelActor");
            assertThrows(IllegalArgumentException.class,
                    () -> connection.message("FrameCreditRequested", Boolean.TRUE));
            assertThrows(IllegalArgumentException.class,
                    () -> connection.message("FrameCreditCompleted", 2L));
            assertThrows(IllegalArgumentException.class,
                    () -> channel.message("Activated"));
            assertThrows(IllegalArgumentException.class,
                    () -> channel.message("DeliveryTagRequested", 99L));
            var actor = channel.spawn(runtime, IsolatePolicy.developer());
            actor.ready().get(20, TimeUnit.SECONDS);
            actor.sendAsync(connection.message("FrameCreditRequested", 10L))
                    .get(20, TimeUnit.SECONDS);
            assertTrue(actor.awaitTermination(20, TimeUnit.SECONDS),
                    "wrong nominal type must fail the receiving actor");
            assertTrue(actor.failure().isPresent());
            var replacement = channel.spawn(runtime, IsolatePolicy.developer());
            replacement.ready().get(20, TimeUnit.SECONDS);
            assertEquals(0L, roundtrip(replacement,
                    channel.message("DeliveryTagRequested")));
            replacement.stop();
            assertTrue(replacement.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void negativeMutationsRejectNonExhaustiveAndPrivilegedMessageBodies()
            throws Exception {
        String conn = fixture("connection");
        String channel = fixture("channel");
        String[] connectionMutations = {
                conn.replace("FrameCreditRequested(int bytes);",
                        "FrameCreditRequested(bool bytes);"),
                conn.replace("FrameCreditRequested(int bytes);",
                        "FrameCreditRequested(int bytes); FrameCreditRequested(int other);"),
                conn.replace("on ConnectionMail.CloseRequested as () -> {",
                        "on _ -> {"),
                conn.replace("on ConnectionMail.CloseRequested as () -> {",
                        "on ChannelMail.CloseRequested as () -> {"),
                conn.replace("on ConnectionMail.FrameCreditRequested as (bytes) -> {",
                        "on ConnectionMail.FrameCreditRequested as (expected_generation) -> {"),
                conn.replace("on ConnectionMail.FrameCreditRequested as (bytes) -> {",
                        "on ConnectionMail.FrameCreditRequested as (bytes, extra) -> {"),
                conn.replace("c.send(c.reserve_frame_credit(bytes))",
                        "c.send(c.reserve_frame_credit(true))"),
                conn.replace("c.send(c.reserve_frame_credit(bytes))",
                        "c.send(super.reserve_frame_credit(bytes))"),
                conn.replace("c.send(c.reserve_frame_credit(bytes))",
                        "c.send(c.reserve_frame_credit)"),
                conn.replace("let bool authenticated : false;",
                        "let bool authenticated : true;"),
                conn.replace("s.in_flight_bytes = s.in_flight_bytes + bytes;",
                        "s.authenticated = true; s.in_flight_bytes = s.in_flight_bytes + bytes;"),
                conn.replace("external mtd (ConnectionActor c) receive",
                        "pub external mtd (ConnectionActor c) receive"),
                conn.replace("on ConnectionMail.CloseRequested as () -> {",
                        "on ConnectionMail.FrameCreditRequested as () -> {")
        };
        String[] channelMutations = {
                channel.replace("let bool activated : false;",
                        "let bool activated : true;"),
                channel.replace("s.next_delivery_tag = s.next_delivery_tag + 1;",
                        "s.activated = true; s.next_delivery_tag = s.next_delivery_tag + 1;"),
                channel.replace("on ChannelMail.CloseRequested as () -> {",
                        "on ChannelMail.DeliveryTagRequested as () -> {")
        };
        for (String invalid : connectionMutations) {
            assertNotEquals(conn, invalid, "negative test must actually mutate source");
            try (var store = new SharedCodeImageStore()) {
                assertThrows(RuntimeException.class,
                        () -> compile(store, "src/actors/connection.atto",
                                invalid, "ConnectionActor"));
            }
        }
        for (String invalid : channelMutations) {
            assertNotEquals(channel, invalid);
            try (var store = new SharedCodeImageStore()) {
                assertThrows(RuntimeException.class,
                        () -> compile(store, "src/actors/channel.atto",
                                invalid, "ChannelActor"));
            }
        }
    }
}

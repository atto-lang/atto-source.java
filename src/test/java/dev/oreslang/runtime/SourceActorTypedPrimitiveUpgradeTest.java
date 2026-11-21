package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Product source compiler -> ABI proof -> immutable ORM2 admission ->
 * physical SHARED ActorRuntime -> exact PROCESSED receipt -> upgrade tests.
 * Distinct Truffle contexts do not constitute protected worker isolation.
 */
@Timeout(70)
final class SourceActorTypedPrimitiveUpgradeTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String STRING_V1 = """
            define actor TypedSink as
              let string last;
              on_start(): void { self.last = ""; return; }
              receive(ActorMail<string> mail): void {
                self.last = mail.value;
                self.send(self.last);
                return;
              }
            end
            pub routine main(): void { return; }
            """;
    private static final String STRING_V2 = STRING_V1.replace(
            "self.last = \"\";", "self.last = \"wrong-new-startup\";");
    private static final String BOOL_V1 = """
            define actor TypedSink as
              let bool last;
              on_start(): void { self.last = false; return; }
              receive(ActorMail<bool> mail): void {
                self.last = mail.value;
                self.send(self.last);
                return;
              }
            end
            pub routine main(): void { return; }
            """;
    private static final String BOOL_V2 = BOOL_V1.replace(
            "self.last = false;", "self.last = true;");
    private static final String INT_V1 = """
            define actor TypedSink as
              let int last;
              on_start(): void { self.last = 0; return; }
              receive(ActorMail<int> mail): void {
                self.last = mail.value;
                self.send(self.last);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    private static Object next(LogicalSourceActorProxyBridgeV1 bridge)
            throws Exception {
        var output = bridge.nextOutput(WAIT);
        assertFalse(output.done());
        return output.value().value();
    }

    @Test
    void sourceCompilerProvesExactlyTheSupportedPrimitiveKinds() throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var integer = manager.stageActorGeneration("orm2-int.ores", INT_V1);
            var text = manager.stageActorGeneration("orm2-string.ores", STRING_V1);
            var flag = manager.stageActorGeneration("orm2-bool.ores", BOOL_V1);
            assertEquals(ActorRuntime.ProxyWireScalarKind.INT,
                    integer.linkActorGeneration("TypedSink").executor()
                            .actorProxyWireScalarKind("TypedSink").orElseThrow());
            assertEquals(ActorRuntime.ProxyWireScalarKind.STRING,
                    text.linkActorGeneration("TypedSink").executor()
                            .actorProxyWireScalarKind("TypedSink").orElseThrow());
            assertEquals(ActorRuntime.ProxyWireScalarKind.BOOL,
                    flag.linkActorGeneration("TypedSink").executor()
                            .actorProxyWireScalarKind("TypedSink").orElseThrow());
        }
    }

    @Test
    void stringMessagesRemainImmutableAndCompatibleAcrossRealUpgrade()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("orm2-text-v1.ores", STRING_V1);
            var second = manager.stageActorGeneration("orm2-text-v2.ores", STRING_V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedTypedPrimitive(
                    first, "TypedSink", WAIT, 4, 256, 1024)) {
                var logical = bridge.address();
                assertThrows(SecurityException.class, () -> bridge.admitLong(1L));
                assertThrows(SecurityException.class, () -> bridge.admitBoolean(true));
                assertEquals(1L, bridge.snapshot().nextMessageSequence());

                bridge.admitString("first \u00e9");
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals("first \u00e9", next(bridge));
                var queued = bridge.admitString("queued \ud83d\ude42");
                assertEquals(SourceActorTypedWireV2.HEADER_BYTES
                                + "queued \ud83d\ude42".getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8).length,
                        queued.payload().length);
                assertTrue(bridge.upgradeTo(second, WAIT));
                assertEquals(logical.actorId(), bridge.address().actorId());
                assertEquals(logical.incarnation(), bridge.address().incarnation());
                assertEquals(2L, bridge.address().routeEpoch());
                assertEquals(1, bridge.snapshot().queuedMessages());

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals("queued \ud83d\ude42", next(bridge));
                bridge.admitString("after");
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals("after", next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(3L, bridge.snapshot().completionWatermark());
            }
        }
    }

    @Test
    void booleanAndIntegerReceiveTypesCannotBeConfused()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var boolV1 = manager.stageActorGeneration("orm2-bool-v1.ores", BOOL_V1);
            var boolV2 = manager.stageActorGeneration("orm2-bool-v2.ores", BOOL_V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedTypedPrimitive(
                    boolV1, "TypedSink", WAIT, 4, 128, 512)) {
                assertThrows(SecurityException.class, () -> bridge.admitLong(1L));
                assertThrows(SecurityException.class, () -> bridge.admitString("true"));
                bridge.admitBoolean(true);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(true, next(bridge));
                bridge.admitBoolean(false);
                assertTrue(bridge.upgradeTo(boolV2, WAIT));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(false, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
            var integer = manager.stageActorGeneration("orm2-int-v1.ores", INT_V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedTypedPrimitive(
                    integer, "TypedSink", WAIT, 2, 128, 256)) {
                assertThrows(SecurityException.class, () -> bridge.admitBoolean(false));
                assertThrows(SecurityException.class, () -> bridge.admitString("x"));
                bridge.admitLong(Long.MIN_VALUE);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(Long.MIN_VALUE, next(bridge));
            }
        }
    }

    @Test
    void malformedOrm2FrameIsRejectedBeforePhysicalSendReservation()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "orm2-tampered-frame.ores", STRING_V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         generation, ActorRuntime.ActorKind.SHARED,
                         "TypedSink", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(3, 128, 384)) {
                router.bindProxyMailboxForSupervisor(mailbox, false, true);
                var binding = router.typedWireBindingForProxy(mailbox);
                byte[] malformed = SourceActorTypedWireV2.encodeString(
                        binding, "hello", 128);
                malformed[32] ^= 1; // protected compiler message ABI header
                var envelope = mailbox.admit(malformed);
                assertSame(envelope, mailbox.pollDispatch());
                var before = mailbox.snapshot();
                assertThrows(SecurityException.class,
                        () -> router.sendAndDrainForProxy(
                                mailbox, envelope, before.routeEpoch()));
                assertEquals(before, mailbox.snapshot(),
                        "invalid wire may not submit a physical actor message");
                // The absence of a physical-send attempt is proven by the
                // mailbox's own trusted ledger; no ambiguous replay is allowed.
                mailbox.requeueProvenUnsent(envelope.messageSequence());
                assertEquals(1, mailbox.snapshot().queuedMessages());
                assertEquals(0, mailbox.snapshot().outstandingMessages());
                assertEquals(1, mailbox.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void incompatibleCompilerTypeAndOversizedStringFailBeforeMailboxMutation()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("orm2-sink-v1.ores", STRING_V1);
            var incompatible = manager.stageActorGeneration("orm2-sink-int.ores", INT_V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedTypedPrimitive(
                    first, "TypedSink", WAIT, 3, 96, 288)) {
                var before = bridge.snapshot();
                assertThrows(IllegalArgumentException.class,
                        () -> bridge.admitString("a".repeat(96)));
                assertThrows(IllegalArgumentException.class,
                        () -> bridge.admitString(
                                new String(new char[] {(char) 0xd800})));
                assertEquals(before, bridge.snapshot(),
                        "invalid byte or UTF-8 admission must leave credits unchanged");

                assertThrows(SecurityException.class,
                        () -> bridge.upgradeTo(incompatible, WAIT),
                        "compiler kind/ABI mismatch must refuse physical publication");
                assertEquals(1L, bridge.address().routeEpoch());
                assertEquals(1L, bridge.snapshot().routeEpoch());
                assertEquals(0, bridge.snapshot().retainedMessages());
                // Any failing migration attempt must seal this particular
                // supervisor rather than silently dispatch on uncertain code.
                assertTrue(bridge.failSealed());
            }
        }
    }
}

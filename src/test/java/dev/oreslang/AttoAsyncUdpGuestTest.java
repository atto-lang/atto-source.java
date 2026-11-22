package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Atto guest source exercises the actual typechecker, Truffle evaluator,
 * NIO reactor and physical UDP loopback. Not just a host-model test.
 * Requires exact-head JDK/GraalVM CI before this can be marked passed.
 */
@Timeout(20)
final class AttoAsyncUdpGuestTest {
    private static final String LOOPBACK = """
            pub async routine main(): int {
              val socket = await network.bind_udp("127.0.0.1", 0);
              val port = socket.local_port();
              val sent = await socket.send_to(port, [42, 0, 255]);
              val packet = await socket.recv_from(16);
              val bytes = packet.bytes();
              val from_port = packet.source_port();
              await socket.close();
              if sent != 3 then return 1; fi
              if from_port != port then return 2; fi
              if bytes[0] != 42 then return 3; fi
              if bytes[1] != 0 then return 4; fi
              if bytes[2] != 255 then return 5; fi
              return 42;
            }
            """;

    @Test void compiledAttoGuestReceivesOwnUdpDatagram() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(LOOPBACK));
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, new String[] {"--ores-capabilities=NETWORK"})
                .build()) {
            // Existing interpreter entrypoint executes guest main.
            // Some embeddings expose an empty eval result for a main return:
            // check source execution without assuming a polyglot result type.
            org.graalvm.polyglot.Value value = context.eval(Source.newBuilder(
                    OresLanguage.ID, LOOPBACK, "udp-guest-loopback.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            assertTrue(value.fitsInInt(), "async Atto guest must return its status");
            assertEquals(42, value.asInt(), "real UDP guest must complete every packet assertion");
        }
    }

    @Test void udpRejectsLegacyBlockingAndUnrelatedSocketMethods() {
        String bad = """
                pub async routine main(): void {
                  val socket = await network.bind_udp("127.0.0.1", 0);
                  val bytes = socket.read_line();
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(bad));
    }

    @Test void udpRejectsStringPayloadAtTypecheck() {
        String bad = """
                pub async routine main(): void {
                  val socket = await network.bind_udp("127.0.0.1", 0);
                  await socket.send_to(9000, "not bytes");
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(bad));
    }

    @Test void udpBindWithoutNetworkCapabilityFailsClosed() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(LOOPBACK));
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            assertThrows(org.graalvm.polyglot.PolyglotException.class, () ->
                context.eval(Source.newBuilder(OresLanguage.ID, LOOPBACK,
                        "udp-denied-no-network.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build()));
        }
    }

    @Test void udpRejectsOutOfRangeGuestByteWithoutSending() {
        String badPayload = """
                pub async routine main(): int {
                  val socket = await network.bind_udp("127.0.0.1", 0);
                  val port = socket.local_port();
                  await socket.send_to(port, [256]);
                  await socket.close();
                  return 0;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(badPayload));
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, new String[] {"--ores-capabilities=NETWORK"})
                .build()) {
            assertThrows(org.graalvm.polyglot.PolyglotException.class, () ->
                context.eval(Source.newBuilder(OresLanguage.ID, badPayload,
                        "udp-bad-octet.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build()));
        }
    }

    @Test void udpRejectsNonNumericHostDespiteNetworkCapability() {
        String host = """
                pub async routine main(): void {
                  val socket = await network.bind_udp("localhost", 0);
                  await socket.close();
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(host));
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID, new String[] {"--ores-capabilities=NETWORK"})
                .build()) {
            assertThrows(org.graalvm.polyglot.PolyglotException.class, () ->
                context.eval(Source.newBuilder(OresLanguage.ID, host,
                        "udp-nonnumeric-host.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build()));
        }
    }

    @Test void udpRejectsStringReceiveCapacityAtTypecheck() {
        String bad = """
                pub async routine main(): void {
                  val socket = await network.bind_udp("127.0.0.1", 0);
                  val packet = await socket.recv_from("unbounded");
                  return;
                }
                """;
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(bad));
    }
}

package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real Atto state ownership and multiple Redis commands on NIO2 TCP. */
final class AttoRedisAsyncNioSingleMailGuestTest {
    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] part : parts) result.write(part);
        return result.toByteArray();
    }

    private static byte[] command(byte[]... parts) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(ascii("*" + parts.length + "\r\n"));
        for (byte[] part : parts) {
            result.write(ascii("$" + part.length + "\r\n"));
            result.write(part);
            result.write(ascii("\r\n"));
        }
        return result.toByteArray();
    }

    @Test void realAttoActorRunsRedisOverAsyncNioAndPreservesBinary() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByAddress(new byte[] {127,0,0,1}))) {
            port = probe.getLocalPort();
        }

        Path guestPath = Path.of("src/test/resources/atto-redis-async-nio/redis_actor_nio_single_mail.atto");
        String source = Files.readString(guestPath).replace("__PORT__", Integer.toString(port));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source),
                "The actual Atto Redis actor guest must compile, not just a Java mock");

        try (ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> guest = runner.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .arguments(OresLanguage.ID, new String[] {
                                "--ores-capabilities=NETWORK,ACTOR_SPAWN,SHARED_MEMORY"})
                        .build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, source,
                            "redis_actor_nio_single_mail.atto")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            Socket client = null;
            for (int attempt = 0; attempt < 120; attempt++) {
                if (guest.isDone()) {
                    guest.get(1, TimeUnit.SECONDS);
                    break;
                }
                try {
                    client = new Socket("127.0.0.1", port);
                    break;
                } catch (java.net.ConnectException starting) {
                    Thread.sleep(50); // Test client only, not the Atto/actor I/O path.
                }
            }
            assertNotNull(client, "real Atto guest failed to bind NIO2 listener");
            try (Socket connectedClient = client) {
                connectedClient.setSoTimeout(8000);
                byte[] binaryKey = {0, (byte) 0xff, '\r', '\n'};
                byte[] binaryValue = {0, (byte) 0xfd, 1, '\n', '\r'};
                byte[] missing = command(ascii("GET"), binaryKey);
                connectedClient.getOutputStream().write(missing);
                assertArrayEquals(ascii("$-1\r\n"), connectedClient.getInputStream().readNBytes(5));

                byte[] insert = command(ascii("SET"), binaryKey, binaryValue);
                connectedClient.getOutputStream().write(insert, 0, 7);
                connectedClient.getOutputStream().flush();
                Thread.sleep(30); // Deliberately fragment request at header boundary.
                connectedClient.getOutputStream().write(insert, 7, insert.length - 7);

                // Pipeline the remaining commands while the actual Atto actor
                // owns all Map mutation and message sequencing.
                byte[] pipeline = concat(
                        command(ascii("GET"), binaryKey),
                        command(ascii("DEL"), binaryKey),
                        command(ascii("GET"), binaryKey),
                        command(ascii("SET"), ascii("empty"), new byte[0]),
                        command(ascii("GET"), ascii("empty")),
                        command(ascii("PING")),
                        command(ascii("WAT")),
                        command(ascii("QUIT")));
                connectedClient.getOutputStream().write(pipeline);
                connectedClient.getOutputStream().flush();

                byte[] expected = concat(
                        ascii("+OK\r\n"),
                        ascii("$5\r\n"), binaryValue, ascii("\r\n"),
                        ascii(":1\r\n"), ascii("$-1\r\n"),
                        ascii("+OK\r\n"), ascii("$0\r\n\r\n"),
                        ascii("+PONG\r\n"),
                        ascii("-ERR unsupported command\r\n"),
                        ascii("+OK\r\n"));
                byte[] replies = connectedClient.getInputStream().readNBytes(expected.length);
                assertArrayEquals(expected, replies,
                        "Atto actor RESP2 binary SET/GET/DEL, missing vs empty, pipeline and QUIT");
            }
            guest.get(15, TimeUnit.SECONDS);
        }
    }
}

package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@Timeout(20)
@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ_WRITE)
final class NioTcpSourceExecutionTest {
    @TempDir Path temp;

    @Test
    void strictSourceConnectAsyncWritesThroughVmSelector() throws Exception {
        String previous = System.getProperty("ores.runtime.io.mode");
        System.setProperty("ores.runtime.io.mode", "nio-only");

        try (ServerSocket server = new ServerSocket(
                0,
                8,
                InetAddress.getByName("127.0.0.1"))) {
            FutureTask<List<Integer>> received = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(5_000);
                    return List.of(
                            peer.getInputStream().read(),
                            peer.getInputStream().read(),
                            peer.getInputStream().read());
                }
            });
            Thread peerThread = Thread.ofPlatform()
                    .daemon(true)
                    .name("nio-source-loopback-peer")
                    .start(received);

            Path entry = temp.resolve("main.ores");
            Files.writeString(entry, """
                    pub fnc main(): void {
                      val Future<NioTcpConnection> pending =
                          network.connect_async("127.0.0.1", %d);
                      val NioTcpConnection socket = await pending;
                      val int sent =
                          await socket.write_bytes_async([65, 66, 67]);
                      stdio.stdout.write(sent);
                      await socket.close_async();
                      return;
                    }
                    """.formatted(server.getLocalPort()));

            IsolatePolicy policy = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK);
            RuntimePermissions permissions = RuntimePermissions.denyAll()
                    .withAllowed(
                            RuntimePermissions.Permission.NET,
                            "127.0.0.1:" + server.getLocalPort());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            LinkedProgramRunner.run(
                    entry,
                    policy,
                    permissions,
                    PermissionCheckMode.COMPILE,
                    ExecutionProfile.serverJit(),
                    Set.of(),
                    Map.of(),
                    out,
                    err);

            assertEquals("3", out.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(
                    List.of(65, 66, 67),
                    received.get(5, TimeUnit.SECONDS));
            peerThread.join(5_000);
            assertFalse(peerThread.isAlive());
        } finally {
            if (previous == null) {
                System.clearProperty("ores.runtime.io.mode");
            } else {
                System.setProperty("ores.runtime.io.mode", previous);
            }
        }
    }
}

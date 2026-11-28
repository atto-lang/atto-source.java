package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine backend-owned .atto module import -> compiler -> guest -> UDP NIO.
 * This intentionally fails if the import, return type, actor ownership, or
 * physical guest socket path cannot be lowered. NOT a source-model test.
 * No pass may be claimed without exact-head JDK25/GraalVM execution.
 */
@Timeout(30)
final class AttoUdpNamespaceImportGuestTest {
    @TempDir Path temp;

    @Test void importedUdpNamespaceRunsRealLoopbackAndReturns42() throws Exception {
        Path library = Path.of("stdlib/net/udp_loopback.atto");
        assertTrue(Files.isRegularFile(library), "missing backend-owned .atto UDP stdlib");
        Files.copy(library, temp.resolve("udp.atto"));
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import module Udp from "./udp.atto";
                pub async routine main(): int {
                  val socket = await Udp.bind_loopback(0);
                  val local_port = socket.local_port();
                  val sent = await socket.send_to(local_port, [42, 0, 255]);
                  val packet = await socket.recv_from(16);
                  val received = packet.bytes();
                  val source_port = packet.source_port();
                  await socket.close();
                  if sent != 3 then return 1; fi
                  if source_port != local_port then return 2; fi
                  if received[0] != 42 then return 3; fi
                  if received[1] != 0 then return 4; fi
                  if received[2] != 255 then return 5; fi
                  return 42;
                }
                """);
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(entry, policy,
                permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), Map.of(), output, errors);
        assertEquals(42, result.exitStatus(),
                "linked imported UDP guest must execute physical loopback: " + errors);
    }
}

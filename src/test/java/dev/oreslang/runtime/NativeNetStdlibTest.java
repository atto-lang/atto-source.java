package dev.oreslang.runtime;

import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.interop.MixedSourceUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class NativeNetStdlibTest {
    @TempDir Path root;
    private int run(String body, IsolatePolicy policy) throws Exception {
        Path entry = root.resolve("net.ores");
        Files.writeString(entry, "import module net_core from \"std/net\"; pub async routine main(): int {" + body + "}");
        return LinkedProgramRunner.runWithExitStatus(entry, policy, ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()).exitStatus();
    }
    @Test void sourceFacadeIsOreslangAndEveryIoEntryReturnsAFuture() throws Exception {
        String source = CoreLibrarySources.source("std/net");
        var parsed = MixedSourceUnit.parse(CoreLibrarySources.unitId("std/net"), "net.ores", source);
        assertTrue(parsed.hasOresSource()); assertFalse(parsed.hasJavaSource());
        assertFalse(source.contains("java:")); assertFalse(source.contains("stdio."));
        assertFalse(source.contains("listen_nonblocking"));
        assertEquals(6, source.lines().filter(line -> line.contains("): Future<")).count());
    }
    @Test void deniedNetworkAuthorityFailsBeforeReactorAdmission() {
        var failure = assertThrows(RuntimeException.class, () -> run("""
                val Future<NioTcpListener> opening = net_core.listen_loopback(0, 8, 1, 4);
                val NioTcpListener listener = await opening;
                return 1;
                """, IsolatePolicy.developer()));
        assertTrue(failure.toString().contains("NETWORK"), failure::toString);
    }
    @Test void asynchronousListenerQuotaIncludesPendingOpens() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 17; ++i) body.append("val Future<NioTcpListener> pending")
                .append(i).append(" = net_core.listen_loopback(0, 8, 1, 4);\n");
        body.append("val NioTcpListener denied = await pending16; return 1;");
        var failure = assertThrows(RuntimeException.class, () -> run(body.toString(),
                IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)));
        assertTrue(failure.toString().contains("TCP listener capacity exhausted"), failure::toString);
    }
    @Test void closedListenersReleaseQuotaAndReturnWithoutStdio() throws Exception {
        assertEquals(0, run("""
                for (let int i = 0; i < 20; i = i + 1) {
                  val Future<NioTcpListener> opening = net_core.listen_loopback(0, 8, 1, 4);
                  val NioTcpListener listener = await opening;
                  val Future<void> closed = net_core.close_listener(listener);
                  await closed;
                }
                return 0;
                """, IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)));
    }
    @Test void unawaitedAsynchronousOpenIsOwnedAndRevokedByContextClose() throws Exception {
        var policy = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK);
        for (int iteration = 0; iteration < 30; ++iteration) {
            assertEquals(0, run("val Future<NioTcpListener> pending = net_core.listen_loopback(0, 8, 1, 4); return 0;", policy));
        }
    }

    @Test void synchronousSetupAliasesAndTruncatedPortsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(
                "pub fnc main(): void { val hidden = network.listen_nonblocking; return; }"));
        var failure = assertThrows(RuntimeException.class, () -> run("""
                val Future<NioTcpListener> opening = net_core.listen_loopback(4294967296, 8, 1, 4);
                val NioTcpListener listener = await opening;
                return 1;
                """, IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)));
        assertTrue(failure.toString().contains("port must be 0..65535"), failure::toString);
    }
}

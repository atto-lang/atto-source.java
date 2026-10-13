package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class StrictCoreIoCliTest {
    @TempDir Path root;

    @Test void cliEnforcesStrictModeEvenWithWritePermission() throws Exception {
        Path destination = root.resolve("blocked.txt");
        Path entry = root.resolve("write.ores");
        Files.writeString(entry, "pub fnc main(): void { fs.write_text_async(\""
                + destination + "\", \"bad\"); return; }");
        assertEquals(1, OresMain.exitStatusForArgs(new String[]{
                "--core-io=native-async-only", "--allow-write", entry.toString()}));
        assertFalse(Files.exists(destination));
    }

    @Test void cliAllowsOreslangNetworkSetupAndAsyncClose() throws Exception {
        Path entry = root.resolve("net.ores");
        Files.writeString(entry, """
                import module net_core from "std/net";
                pub async routine main(): void {
                  val NioTcpListener listener = net_core.listen_loopback(0, 8, 1, 4);
                  val Future<void> closed = net_core.close_listener(listener);
                  await closed; return;
                }
                """);
        assertEquals(0, OresMain.exitStatusForArgs(new String[]{
                "--core-io=native-async-only", "--allow-net", entry.toString()}));
    }

    @Test void duplicateAndUnknownCliPoliciesAreUsageErrors() {
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{"--core-io=async"}));
        assertEquals(2, OresMain.exitStatusForArgs(new String[]{
                "--core-io=native-async-only", "--core-io=legacy-blocking"}));
    }
}

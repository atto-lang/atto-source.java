package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class StrictCoreIoPolicyTest {
    @TempDir Path root;

    private Context strictContext() {
        var policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ, IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.NETWORK, IsolatePolicy.Capability.STDIN,
                IsolatePolicy.Capability.JAVA_INTEROP);
        String[] base = policy.applicationArguments(ExecutionProfile.serverJit());
        String[] args = Arrays.copyOf(base, base.length + 1);
        args = base; // No opt-in flag: every context is strict by default.
        return Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                .arguments(OresLanguage.ID, args)
                .in(new InputStream() { public int read() { fail("stdin touched"); return -1; } })
                .out(new OutputStream() { public void write(int b) { fail("stdout touched"); } })
                .build();
    }

    @Test void legacyIoIsRefusedBeforePathResolutionWorkerSubmissionAndStreams() throws Exception {
        Path destination = root.resolve("blocked.txt");
        for (String expression : java.util.List.of(
                "fs.write_text(\"" + destination + "\", \"bad\")",
                "fs.write_text_async(\"" + destination + "\", \"bad\")",
                "fs.read_text(\"/no-such-file-ores-async-only\")",
                "fs.exists_async(\"/no-such-file-ores-async-only\")",
                "network.connect(\"localhost\", 1)",
                "network.connect_async(\"localhost\", 1)",
                "http.get_text_async(\"http://localhost:1/\")",
                "http.listen(\"127.0.0.1\", 0, 2, 1024, 1000)",
                "stdio.println(1)", "stdio.stdin.read_line()")) {
            try (Context context = strictContext()) {
                var failure = assertThrows(PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, "pub fnc main(): void { " + expression + "; return; }"), expression);
                assertTrue(failure.getMessage().contains("blocking I/O"), failure::toString);
            }
        }
        assertFalse(Files.exists(destination));
    }

    @Test void aliasesCannotHideTheRuntimePolicy() {
        try (Context context = strictContext()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, """
                    pub fnc main(): void {
                      val hidden = fs.exists_async;
                      hidden("/tmp"); return;
                    }
                    """));
            assertTrue(failure.getMessage().contains("blocking I/O"), failure::toString);
        }
    }

    @Test void javaImportsCannotBypassStrictCoreIo() {
        try (Context context = strictContext()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval(OresLanguage.ID, """
                    import class System from "java:java.lang.System";
                    pub fnc main(): void { System.currentTimeMillis(); return; }
                    """));
            assertTrue(failure.getMessage().contains("Java interop"), failure::toString);
        }
    }

    @Test void strictLinkedOresNetworkingCanOpenAndCloseWithoutLegacyIo() throws Exception {
        Path entry = root.resolve("strict-net.ores");
        Files.writeString(entry, """
                import module net_core from "std/net";
                pub async routine main(): void {
                  val Future<NioTcpListener> opening = net_core.listen_loopback(0, 8, 1, 4);
                  val NioTcpListener listener = await opening;
                  val Future<void> stopped = net_core.close_listener(listener);
                  await stopped; return;
                }
                """);
        var policy = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK);
        assertEquals(0, LinkedProgramRunner.runWithExitStatus(entry, policy,
                RuntimePermissions.fromCapabilities(policy.capabilities()), PermissionCheckMode.COMPILE,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream(), CoreIoPolicy.NATIVE_ASYNC_ONLY).exitStatus());
    }

    @Test void defaultIsStrictAndLegacyCannotBeRequested() {
        assertEquals(CoreIoPolicy.NATIVE_ASYNC_ONLY, CoreIoPolicy.fromApplicationArguments(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> CoreIoPolicy.fromApplicationArguments(
                new String[]{"--ores-core-io=legacy-blocking"}));
    }

    @Test void retiredWorkerCannotExecuteEvenEmbeddingWork() throws Exception {
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        try (var worker = new AsyncNativeIo()) {
            var future = worker.submit("legacy work", () -> { ran.set(true); return 1; });
            var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(UnsupportedOperationException.class, rejected.getCause());
            assertFalse(ran.get());
        }
    }

    @Test void invalidOrDuplicatePoliciesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> CoreIoPolicy.fromApplicationArguments(
                new String[]{"--ores-core-io=async"}));
        assertThrows(IllegalArgumentException.class, () -> CoreIoPolicy.fromApplicationArguments(
                new String[]{"--ores-core-io=native-async-only", "--ores-core-io=legacy-blocking"}));
    }
}

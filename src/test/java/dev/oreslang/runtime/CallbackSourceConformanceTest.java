package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

final class CallbackSourceConformanceTest {

    private static final String RESOURCE =
            "/conformance/execution-context-callbacks/v1/callback_sync_resolve.ores";
    private static final String EXPECTED_SOURCE_BLOB =
            "83f7db76fd6437d7d4994210e78034b93a5bff26";
    private static final String ATTACH_RESOURCE =
            "/conformance/execution-context-callbacks/v1/callback_attach_chain.ores";
    private static final String EXPECTED_ATTACH_SOURCE_BLOB =
            "80429c782be6a32d5f3699d0f624f15c77ce3623";

    @Test
    void exactSyncResolveFixtureCompilesAndExecutesThroughRealSourceRunner()
            throws Exception {
        URL resource = CallbackSourceConformanceTest.class.getResource(RESOURCE);
        assertNotNull(resource, "missing canonical callback source fixture");
        assertEquals("file", resource.getProtocol(),
                "test fixture must be materialized as a real source file");

        Path source = Path.of(resource.toURI());
        assertEquals(EXPECTED_SOURCE_BLOB, gitBlobId(Files.readAllBytes(source)),
                "canonical callback source fixture drifted");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        LinkedProgramRunner.RunResult result = LinkedProgramRunner.runWithExitStatus(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                stdout,
                stderr);

        assertEquals(0, result.exitStatus());
        assertEquals(
                String.join("\n",
                        "before_callback",
                        "callback_enter",
                        "callback_exit",
                        "after_callback",
                        "after_await",
                        "42",
                        ""),
                stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void callbackChainingCompilesAndExecutesThroughOwnedSchedulerTurns()
            throws Exception {
        URL resource = CallbackSourceConformanceTest.class.getResource(ATTACH_RESOURCE);
        assertNotNull(resource, "missing callback chaining source fixture");
        assertEquals("file", resource.getProtocol());

        Path source = Path.of(resource.toURI());
        assertEquals(
                EXPECTED_ATTACH_SOURCE_BLOB,
                gitBlobId(Files.readAllBytes(source)),
                "callback chaining fixture drifted");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LinkedProgramRunner.RunResult result = LinkedProgramRunner.runWithExitStatus(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                stdout,
                stderr);

        assertEquals(0, result.exitStatus());
        assertEquals("42\n", stdout.toString(StandardCharsets.UTF_8));
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
    }

    private static String gitBlobId(byte[] bytes) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        byte[] digest = sha1.digest(bytes);
        return java.util.HexFormat.of().formatHex(digest);
    }
}

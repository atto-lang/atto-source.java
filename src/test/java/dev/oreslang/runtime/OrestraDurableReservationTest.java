package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class OrestraDurableReservationTest {
    private static final String FIXTURE_BLOB =
            "7623c12c725f41d16f0b62aa7283676f68992132";

    @TempDir Path root;

    @Test
    void durableWalCommitPrecedesReservationMutationAndAck() throws Exception {
        Assumptions.assumeTrue(
                Files.getFileStore(root).supportsFileAttributeView("unix"),
                "M1 durable WAL contract requires Unix link metadata");

        byte[] templateBytes;
        try (InputStream input = getClass().getResourceAsStream(
                "/orestra/durable-reservation.ores")) {
            assertNotNull(input, "durable Orestra fixture missing");
            templateBytes = input.readAllBytes();
        }
        assertEquals(FIXTURE_BLOB, gitBlobSha(templateBytes),
                "durable Orestra fixture drifted from reviewed Git blob");

        Path wal = root.resolve("orestra-state.wal");
        String template = new String(templateBytes, StandardCharsets.UTF_8);
        String program = template.replace("__WAL_PATH__", q(wal));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));

        String output = run(program);
        assertEquals(
                "reserve-ok\nrelease-ok\ndurable-state-ok\n",
                output);
        assertEquals(
                "reserve|revision=1|cpu=2|epoch=7\n"
                        + "release|revision=2|cpu=1|epoch=7\n",
                Files.readString(wal));
    }

    private String run(String program) throws Exception {
        Path source = root.resolve("orestra-durable-reservation.ores");
        Files.writeString(source, program, StandardCharsets.UTF_8);

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                System.getenv(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String q(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static String gitBlobSha(byte[] content) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + content.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        sha1.update(content);
        return HexFormat.of().formatHex(sha1.digest());
    }
}

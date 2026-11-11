package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the REAL Java/GraalVM AttoLang guest, not a host-side KV emulator.
 *
 * The reviewed source blobs are copied byte-for-byte from the private
 * atto-redis-clone.atto guest/ corpus. The mutable KV cell is a deliberately
 * one-key, in-memory admission gate; FS smoke is NOT a durable binary WAL.
 */
@Timeout(30)
final class AttoRedisGuestVerticalSliceTest {
    private static final Path CORPUS =
            Path.of("src/test/resources/atto/redis-guest-v1");
    @TempDir Path temp;

    private static String gitBlob(byte[] data) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        sha.update(data);
        return HexFormat.of().formatHex(sha.digest());
    }

    private static byte[] checkedBytes(String name, String sha) throws Exception {
        Path path = CORPUS.resolve(name);
        assertFalse(Files.isSymbolicLink(path), "fixture must not be a symlink");
        assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "fixture missing");
        byte[] bytes = Files.readAllBytes(path);
        assertTrue(bytes.length > 0 && bytes.length <= 8192, "oversized fixture");
        assertEquals(sha, gitBlob(bytes), "Atto Redis source blob drift: " + name);
        return bytes;
    }

    private static String output(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void inMemoryLsnTombstoneRulesCompileAndExecuteAsRealAttoGuest() throws Exception {
        byte[] source = checkedBytes(
                "kv_cell.atto", "6a075fc0f0f36a4bb04cebc98dabfb183898f43f");
        byte[] expected = checkedBytes(
                "kv_cell.expected", "7334c798cebd19997a8ffe131f633d350d6203bc");
        Path entry = temp.resolve("kv_cell.atto");
        Files.write(entry, source);

        // LinkedProgramRunner performs real linked AttoLang type/ownership
        // checking and runs the source through the guest interpreter.
        assertEquals(1, LinkedProgramRunner.validate(entry, Map.of()).units().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
        assertEquals(new String(expected, StandardCharsets.UTF_8),
                output(out), "guest output mismatch: " + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void filesystemRoundtripIsRealCapabilityScopedGuestButNotFsync() throws Exception {
        String template = new String(checkedBytes(
                "fs_roundtrip.atto", "fb1c2fba1c0ec48681b2c086f41ec2d4f0b37e31"),
                StandardCharsets.UTF_8);
        byte[] expected = checkedBytes(
                "fs_roundtrip.expected", "27ba77ddaf61532f217964734769a42cdabc17dc");
        Path root = Files.createDirectory(temp.resolve("disk")).toRealPath();
        Path target = root.resolve("wal-smoke.txt");
        String safeTarget = target.toString()
                .replace("\\", "\\\\").replace("\"", "\\\"");
        assertEquals(3, template.split("__ATTO_REDIS_IO_FILE__", -1).length - 1);
        String source = template.replace("__ATTO_REDIS_IO_FILE__", safeTarget);
        Path entry = temp.resolve("fs_roundtrip.atto");
        Files.writeString(entry, source);

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_READ,
                                  IsolatePolicy.Capability.FILESYSTEM_WRITE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, policy, permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(), out, err);
        assertEquals(new String(expected, StandardCharsets.UTF_8),
                output(out), "guest FS output mismatch: " + err.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(target), "guest FS cleanup did not run");
    }
}

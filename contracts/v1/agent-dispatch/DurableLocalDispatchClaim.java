// HOST-ONLY same-machine, private-filesystem durable one-shot claim.
// Not a general distributed CAS, actual provider receipt, or guest API.
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.Set;
import static java.nio.file.StandardOpenOption.*;

public final class DurableLocalDispatchClaim {
    private static final Set<PosixFilePermission> PRIVATE_DIR = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final Path directory;
    public DurableLocalDispatchClaim(Path directory) { this.directory = directory; }

    private static boolean validId(String v) {
        return v != null && v.matches("[A-Za-z0-9_.:@/-]{1,128}");
    }
    private static boolean validHex(String v, int size) {
        return v != null && v.length() == size && v.matches("[a-f0-9]+");
    }
    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String claimFilename(AgentHostDispatchGate.Scope s) {
        if (s == null || !validId(s.tenantId()) || !validId(s.actorId()) ||
            !validId(s.dispatchId()) || !validHex(s.exactGitRevision(), 40) ||
            !validHex(s.contextSha256(), 64) ||
            s.actorGeneration() <= 0 || s.activationEpoch() <= 0)
            throw new IllegalArgumentException("invalid trusted claim scope");
        // Newline cannot occur in IDs. Keep the content digest OUT of the
        // filename key; stable logical actor identity survives generations/revisions.
        // This key blocks changed payloads and actor-restart replays alike.
        String key = "ores-agent-dispatch-claim-v1\n" + s.tenantId() + "\n" +
            s.actorId() + "\n" + s.dispatchId() + "\n";
        return sha256(key) + ".claim";
    }
    public boolean claimOnce(AgentHostDispatchGate.Scope scope) {
        try {
            String name = claimFilename(scope);
            // Reject symlink components anywhere in the host-provisioned path.
            // A check of only the leaf directory can be bypassed by a linked
            // ancestor. The parent chain must also be protected against mutation
            // by the host's filesystem permissions; this is not a dirfd API.
            if (directory == null || !directory.isAbsolute() ||
                !directory.normalize().equals(directory)) return false;
            for (Path current = directory; current != null; current = current.getParent()) {
                if (Files.isSymbolicLink(current)) return false;
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
                !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
                    .equals(PRIVATE_DIR) ||
                !Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).equals(
                    Files.getOwner(Path.of(System.getProperty("user.home")), LinkOption.NOFOLLOW_LINKS)))
                return false;
            Path claim = directory.resolve(name);
            byte[] data = ("ORES_AGENT_CLAIM_V1\n" + scope.contextSha256() + "\n")
                .getBytes(StandardCharsets.US_ASCII);
            // CREATE_NEW is an atomic cross-process reservation on a local FS.
            // A partially written claim remains consumed, never replayed.
            try (FileChannel file = FileChannel.open(claim,
                Set.of(CREATE_NEW, WRITE),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE_FILE))) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            // Flush the directory entry before approving the external dispatch.
            try (FileChannel dir = FileChannel.open(directory, READ)) {
                dir.force(true);
            }
            return true;
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException |
                 SecurityException exception) {
            return false;
        }
    }
}

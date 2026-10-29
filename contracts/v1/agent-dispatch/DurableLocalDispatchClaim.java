// HOST-ONLY same-machine, private-filesystem durable one-shot claim.
// Not a general distributed CAS, actual provider receipt, or guest API.
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.charset.StandardCharsets;
import com.sun.security.auth.module.UnixSystem;
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
    // SecureDirectoryStream performs openat-style, directory-descriptor-anchored
    // traversal, claim creation, and directory sync. Reject filesystem providers
    // that cannot supply this capability. No fallback to pathname FileChannel.
    private boolean claimAt(SecureDirectoryStream<Path> current, int index,
                            String name, AgentHostDispatchGate.Scope scope)
                            throws IOException {
        if (index < directory.getNameCount()) {
            Path component = directory.getName(index);
            String segment = component.toString();
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
                segment.indexOf('\0') >= 0) return false;
            try (SecureDirectoryStream<Path> next = current.newDirectoryStream(
                    component, LinkOption.NOFOLLOW_LINKS)) {
                return claimAt(next, index + 1, name, scope);
            }
        }
        PosixFileAttributeView view = current.getFileAttributeView(
            PosixFileAttributeView.class);
        if (view == null) return false;
        PosixFileAttributes attrs = view.readAttributes();
        // OS-authenticated owner, never mutable user.home / user.name props.
        if (!attrs.isDirectory() || !attrs.permissions().equals(PRIVATE_DIR) ||
            !attrs.owner().getName().equals(new UnixSystem().getUsername()))
            return false;
        byte[] data = ("ORES_AGENT_CLAIM_V1\n" + scope.contextSha256() + "\n")
            .getBytes(StandardCharsets.US_ASCII);
        // The claim's O_EXCL is relative to the validated pinned directory FD.
        // Once claimed, even an IO error leaves the name reserved indefinitely.
        try (SeekableByteChannel channel = current.newByteChannel(Path.of(name),
                Set.of(CREATE_NEW, WRITE),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PRIVATE_FILE))) {
            if (!(channel instanceof FileChannel file)) return false;
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
        // Crucial: fsync the *same pinned directory*, not a newly opened
        // pathname which could have been swapped since the claim was created.
        try (SeekableByteChannel channel = current.newByteChannel(
                Path.of("."), Set.of(READ))) {
            if (!(channel instanceof FileChannel dir)) return false;
            dir.force(true);
        }
        return true;
    }
    public boolean claimOnce(AgentHostDispatchGate.Scope scope) {
        try {
            String name = claimFilename(scope);
            if (directory == null || !directory.isAbsolute() ||
                directory.getNameCount() == 0 ||
                !directory.normalize().equals(directory)) return false;
            // Even a symlink in /tmp/parent, not just the leaf, must fail.
            // Secure stream traversals protect against concurrent substitution.
            try (DirectoryStream<Path> root = Files.newDirectoryStream(Path.of("/"))) {
                if (!(root instanceof SecureDirectoryStream<Path> secure))
                    return false;
                return claimAt(secure, 0, name, scope);
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException |
                 SecurityException exception) {
            return false;
        }
    }
}

package dev.oreslang.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted native host-I/O implementations for Oreslang.
 *
 * <p>Every operation checks permissions immediately before touching the host.
 * Guest code never receives Java IO/HTTP/socket objects.</p>
 */
public final class NativeIo {
    private NativeIo() { }

    private static final AtomicReference<String> DURABILITY_FAULT_FOR_TEST =
            new AtomicReference<>();

    static void injectDurabilityFaultForTest(String point) {
        if (point == null || point.isBlank()) {
            throw new IllegalArgumentException("durability fault point cannot be blank");
        }
        DURABILITY_FAULT_FOR_TEST.set(point);
    }

    static void clearDurabilityFaultForTest() {
        DURABILITY_FAULT_FOR_TEST.set(null);
    }

    public static String readText(OresContext context, String rawPath) {
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.read_text");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw ioFailure("read", path, failure);
        }
    }

    public static boolean exists(OresContext context, String rawPath) {
        Path path = canonicalIfExisting(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.exists");
        return Files.exists(path);
    }

    /**
     * Bounded, sorted immediate child names. This is deliberately NOT a path
     * walker: the guest checks each next component with is_symlink and is_dir.
     */
    public static List<String> listDir(OresContext context, String rawPath) {
        Path lexical = Path.of(rawPath).toAbsolutePath().normalize();
        context.requirePermission(RuntimePermissions.Permission.READ, lexical.toString(), "fs.list_dir");
        if (Files.isSymbolicLink(lexical)) throw new SecurityException("fs.list_dir refuses symlink: " + lexical);
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.list_dir");
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("fs.list_dir requires directory: " + path);
        }
        try (java.util.stream.Stream<Path> stream = Files.list(path)) {
            List<String> names = stream.limit(20001).map(p -> p.getFileName().toString()).sorted().toList();
            if (names.size() > 20000) throw new IllegalArgumentException("fs.list_dir exceeds 20000 entries: " + path);
            return new java.util.ArrayList<>(names);
        } catch (IOException failure) {
            throw ioFailure("list directory", path, failure);
        }
    }

    /**
     * Resolve the parent directory before probing an entry. Permission checks
     * cover both the lexical path and its actual parent; a symlinked ancestor
     * cannot be used to inspect names outside the granted source root.
     * Metadata cannot provide race-free traversal on an attacker-writable tree.
     */
    private static Path metadataTarget(OresContext context, String rawPath, String operation) {
        Path lexical = lexicalPath(rawPath);
        Path parent = lexical.getParent();
        if (parent == null) throw new IllegalArgumentException("metadata path has no parent");
        // Metadata only reads the entry in its parent, not the target of a
        // symbolic link. Requiring READ on the child would canonicalize a
        // symlink to its target and incorrectly reject detecting unsafe links.
        // Resolve/check the parent instead; ancestor escapes still fail.
        // Metadata is a non-mutating probe. Missing parent components under an
        // authorized root imply a missing target; do not throw merely because
        // toRealPath() requires every intermediate component to exist.
        // canonicalWritePath resolves only the nearest existing ancestor and
        // preserves the canonical permission boundary. Dangling/escaping
        // symlink ancestors still fail closed during realPath() resolution.
        Path canonicalParent = canonicalWritePath(parent.toString());
        Path target = canonicalParent.resolve(lexical.getFileName());
        try {
            context.requirePermission(RuntimePermissions.Permission.READ,
                    canonicalParent.toString(), operation);
        } catch (SecurityException parentDenied) {
            // A caller may have an exact READ grant for the root directory,
            // without a grant on /tmp or the parent of that root. Allow
            // metadata on that exact ordinary entry but never dereference an
            // ungranted terminal symlink.
            if (Files.isSymbolicLink(target)) throw parentDenied;
            context.requirePermission(RuntimePermissions.Permission.READ,
                    target.toString(), operation);
        }
        return target;
    }

    public static boolean isSymlink(OresContext context, String rawPath) {
        return Files.isSymbolicLink(metadataTarget(context, rawPath, "fs.is_symlink"));
    }

    public static boolean isDir(OresContext context, String rawPath) {
        return Files.isDirectory(metadataTarget(context, rawPath, "fs.is_dir"),
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Generate a complete UTF-8 file beside the destination and replace it
     * atomically. Never open the destination for truncation, nor follow a
     * symlink in the destination slot. The parent is canonicalized and checked
     * against the caller's WRITE grant before any mutation.
     */
    public static void writeTextAtomic(OresContext context, String rawPath, String value) {
        Path lexical = lexicalPath(rawPath);
        if (Files.isSymbolicLink(lexical)) {
            throw new SecurityException("fs.write_text_atomic refuses symlink: " + lexical);
        }
        Path parent = lexical.getParent();
        if (parent == null) throw new IllegalArgumentException("atomic write path has no parent");
        Path canonicalParent = realPath(parent);
        Path target = canonicalParent.resolve(lexical.getFileName());
        context.requirePermission(RuntimePermissions.Permission.WRITE, lexical.toString(), "fs.write_text_atomic");
        context.requirePermission(RuntimePermissions.Permission.WRITE, target.toString(), "fs.write_text_atomic");
        if (!Files.isDirectory(canonicalParent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("atomic write parent is not a directory: " + parent);
        }
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("fs.write_text_atomic refuses destination symlink: " + target);
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(canonicalParent, ".oreslang-stack-", ".tmp");
            Files.writeString(temp, value, StandardCharsets.UTF_8);
            if (Files.isSymbolicLink(target)) {
                throw new SecurityException("fs.write_text_atomic refuses destination symlink: " + target);
            }
            Files.move(temp, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw ioFailure("atomic write", target, failure);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Replace a file only after the new contents have reached the file's
     * durability boundary and the containing directory has acknowledged the
     * rename. This is intentionally synchronous: callers that use it as a
     * transaction commit point must not observe success before both force()
     * operations complete.
     *
     * <p>If the atomic replace may already have happened but directory sync
     * fails, the method throws {@link DurabilityUncertainException}. Callers
     * must not acknowledge the transaction; recovery must inspect durable
     * state before retrying.</p>
     */
    public static void writeTextAtomicDurable(
            OresContext context,
            String rawPath,
            String value) {
        Path lexical = lexicalPath(rawPath);
        if (Files.isSymbolicLink(lexical)) {
            throw new SecurityException(
                    "fs.write_text_atomic_durable refuses symlink: " + lexical);
        }
        Path parent = lexical.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "durable atomic write path has no parent");
        }
        Path canonicalParent = realPath(parent);
        Path target = canonicalParent.resolve(lexical.getFileName());
        context.requirePermission(
                RuntimePermissions.Permission.WRITE,
                lexical.toString(),
                "fs.write_text_atomic_durable");
        context.requirePermission(
                RuntimePermissions.Permission.WRITE,
                target.toString(),
                "fs.write_text_atomic_durable");
        if (!Files.isDirectory(canonicalParent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "durable atomic write parent is not a directory: " + parent);
        }
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException(
                    "fs.write_text_atomic_durable refuses destination symlink: "
                            + target);
        }

        Path temp = null;
        boolean replaceAttempted = false;
        try {
            temp = Files.createTempFile(
                    canonicalParent, ".oreslang-durable-", ".tmp");
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temp,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                writeFully(channel, encoded);
                channel.force(true);
            }

            if (Files.isSymbolicLink(target)) {
                throw new SecurityException(
                        "fs.write_text_atomic_durable refuses destination symlink: "
                                + target);
            }
            replaceAttempted = true;
            Files.move(
                    temp,
                    target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            maybeInjectDurabilityFaultForTest("atomic-after-move", target);

            try {
                syncDirectory(canonicalParent);
            } catch (IOException | RuntimeException syncFailure) {
                throw new DurabilityUncertainException(
                        "durable atomic replace may be visible but parent directory "
                                + "sync failed for " + target,
                        syncFailure);
            }
        } catch (DurabilityUncertainException uncertain) {
            throw uncertain;
        } catch (IOException failure) {
            if (replaceAttempted) {
                throw new DurabilityUncertainException(
                        "durable atomic replace outcome is uncertain for " + target,
                        failure);
            }
            throw ioFailure("durable atomic write", target, failure);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Append UTF-8 bytes and force file contents/metadata before returning.
     * Creating a new WAL file additionally forces its parent directory so the
     * directory entry itself is durable.
     *
     * <p>Existing hard-linked files are rejected on Unix: appending through one
     * name would mutate every alias and violate a WAL's single-path authority.
     * Platforms without Unix link-count metadata fail closed for this M1
     * primitive rather than silently weakening that invariant.</p>
     */
    public static void appendTextDurable(
            OresContext context,
            String rawPath,
            String value) {
        Path lexical = lexicalPath(rawPath);
        if (Files.isSymbolicLink(lexical)) {
            throw new SecurityException(
                    "fs.append_text_durable refuses symlink: " + lexical);
        }
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(
                RuntimePermissions.Permission.WRITE,
                lexical.toString(),
                "fs.append_text_durable");
        context.requirePermission(
                RuntimePermissions.Permission.WRITE,
                path.toString(),
                "fs.append_text_durable");

        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "durable append path has no parent");
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "durable append parent is not a directory: " + parent);
        }

        boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (existed) requireSingleLink(path, "fs.append_text_durable");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS)) {
            writeFully(channel, encoded);
            channel.force(true);
            maybeInjectDurabilityFaultForTest("append-after-force", path);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof SecurityException security) throw security;
            throw new DurabilityUncertainException(
                    "durable append outcome is uncertain for " + path,
                    failure);
        }

        // Creating a new file mutates the directory entry. The file force above
        // is not sufficient to make that new name crash-durable.
        if (!existed) {
            try {
                syncDirectory(parent);
            } catch (IOException | RuntimeException failure) {
                throw new DurabilityUncertainException(
                        "durable append file was forced but parent directory sync "
                                + "failed for " + path,
                        failure);
            }
        }
    }

    public static boolean writeTextAtomicDurableChecked(
            OresContext context,
            String rawPath,
            String value) {
        try {
            writeTextAtomicDurable(context, rawPath, value);
            return true;
        } catch (DurabilityUncertainException uncertain) {
            return false;
        }
    }

    public static boolean appendTextDurableChecked(
            OresContext context,
            String rawPath,
            String value) {
        try {
            appendTextDurable(context, rawPath, value);
            return true;
        } catch (DurabilityUncertainException uncertain) {
            return false;
        }
    }

    /**
     * Acquire a process/context-lifetime exclusive lease on a stable lock file.
     * The underlying FileLock is registered with the context-owned I/O registry
     * and is released automatically when the Ores context closes.
     *
     * <p>This M1 primitive assumes the containing directory is not hostile to
     * the process while the lease is held. Symlink and hard-link aliases fail
     * closed, but unlink-and-replace attacks by another writer with directory
     * mutation authority require a stronger directory-owner protocol.</p>
     */
    public static boolean acquireExclusiveLease(
            OresContext context,
            String rawPath) {
        Path lexical = lexicalPath(rawPath);
        if (Files.isSymbolicLink(lexical)) {
            throw new SecurityException(
                    "fs.acquire_exclusive_lease refuses symlink: " + lexical);
        }
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(
                RuntimePermissions.Permission.WRITE,
                lexical.toString(),
                "fs.acquire_exclusive_lease");
        context.requirePermission(
                RuntimePermissions.Permission.WRITE,
                path.toString(),
                "fs.acquire_exclusive_lease");

        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "exclusive lease parent is not a directory: " + parent);
        }

        boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (existed) requireSingleLink(path, "fs.acquire_exclusive_lease");

        final FileChannel channel;
        try {
            channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw ioFailure("open exclusive lease", path, failure);
        }

        try {
            if (Files.isSymbolicLink(path)) {
                throw new SecurityException(
                        "fs.acquire_exclusive_lease refuses symlink: " + path);
            }
            requireSingleLink(path, "fs.acquire_exclusive_lease");

            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                channel.close();
                return false;
            }
            if (lock == null) {
                channel.close();
                return false;
            }

            ExclusiveFileLease lease = new ExclusiveFileLease(lock, channel);
            try {
                context.nativeIo().register(lease);
            } catch (Exception registrationFailure) {
                try {
                    lease.close();
                } catch (Exception cleanup) {
                    registrationFailure.addSuppressed(cleanup);
                }
                throw new IllegalStateException(
                        "failed to register exclusive WAL lease for " + path,
                        registrationFailure);
            }
            return true;
        } catch (IOException failure) {
            try { channel.close(); } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw ioFailure("acquire exclusive lease", path, failure);
        } catch (RuntimeException failure) {
            try { channel.close(); } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static final class ExclusiveFileLease implements AutoCloseable {
        private final FileLock lock;
        private final FileChannel channel;
        private boolean closed;

        private ExclusiveFileLease(FileLock lock, FileChannel channel) {
            this.lock = lock;
            this.channel = channel;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                if (lock.isValid()) lock.release();
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
    }

    public static void writeText(OresContext context, String rawPath, String value) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.write_text");
        try {
            Files.writeString(
                    path,
                    value,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw ioFailure("write", path, failure);
        }
    }

    public static void appendText(OresContext context, String rawPath, String value) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.append_text");
        try {
            Files.writeString(
                    path,
                    value,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw ioFailure("append", path, failure);
        }
    }

    public static void createDirectories(OresContext context, String rawPath) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.mkdir_all");
        try {
            Files.createDirectories(path);
        } catch (IOException failure) {
            throw ioFailure("create directory", path, failure);
        }
    }

    public static void remove(OresContext context, String rawPath) {
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.remove");
        try {
            Files.delete(path);
        } catch (IOException failure) {
            throw ioFailure("remove", path, failure);
        }
    }

    public static String envGet(OresContext context, String name) {
        validateEnvName(name);
        context.requirePermission(RuntimePermissions.Permission.ENV, name, "env.get");
        return context.env().getEnvironment().get(name);
    }

    public static boolean envHas(OresContext context, String name) {
        validateEnvName(name);
        context.requirePermission(RuntimePermissions.Permission.ENV, name, "env.has");
        return context.env().getEnvironment().containsKey(name);
    }

    public static String httpGetText(OresContext context, String rawUri) {
        return httpText(context, "GET", rawUri, null);
    }

    public static String httpPostText(OresContext context, String rawUri, String body) {
        return httpText(context, "POST", rawUri, body);
    }

    private static String httpText(
            OresContext context,
            String method,
            String rawUri,
            String body) {
        URI uri = checkedHttpUri(rawUri);
        String endpoint = networkEndpoint(uri);
        context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "http." + method.toLowerCase());

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30));
        if ("POST".equals(method)) {
            request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            request.GET();
        }

        // Redirects are deliberately disabled: silently following a redirect
        // to a second host would bypass the permission check above.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        try (client) {
            HttpResponse<String> response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        } catch (IOException failure) {
            throw new IllegalStateException("HTTP request failed for " + endpoint + ": " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted for " + endpoint, interrupted);
        }
    }

    public static TcpConnection connect(OresContext context, String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("network.connect host cannot be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("network.connect port must be 1..65535");
        }
        String endpoint = host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
        context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "network.connect");
        Socket socket = new Socket();
        try {
            context.nativeIo().register(socket);
            socket.connect(new InetSocketAddress(host, port), 15_000);
            return new TcpConnection(context, endpoint, socket);
        } catch (Exception failure) {
            context.nativeIo().unregister(socket);
            try { socket.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("network connection failed for " + endpoint + ": " + failure.getMessage(), failure);
        }
    }

    public static final class TcpConnection implements AutoCloseable {
        private final OresContext context;
        private final String endpoint;
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private TcpConnection(OresContext context, String endpoint, Socket socket) throws IOException {
            this.context = context;
            this.endpoint = endpoint;
            this.socket = socket;
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        public String readLine() {
            context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "network.socket.read_line");
            try {
                return reader.readLine();
            } catch (IOException failure) {
                throw new IllegalStateException("network read failed for " + endpoint + ": " + failure.getMessage(), failure);
            }
        }

        public void writeText(String value) {
            context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "network.socket.write_text");
            try {
                writer.write(value);
                writer.flush();
            } catch (IOException failure) {
                throw new IllegalStateException("network write failed for " + endpoint + ": " + failure.getMessage(), failure);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
                context.nativeIo().unregister(socket);
            } catch (IOException failure) {
                throw new IllegalStateException("network close failed for " + endpoint + ": " + failure.getMessage(), failure);
            }
        }
    }

    private static URI checkedHttpUri(String rawUri) {
        final URI uri;
        try {
            uri = URI.create(rawUri);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid HTTP URL: " + rawUri, invalid);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("http API only accepts http:// or https:// URLs");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("HTTP URL must contain a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("HTTP URLs with embedded credentials are not accepted");
        }
        return uri;
    }

    private static String networkEndpoint(URI uri) {
        int port = uri.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    private static Path canonicalIfExisting(String rawPath) {
        Path lexical = lexicalPath(rawPath);
        if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) return lexical;
        return realPath(lexical);
    }

    private static Path canonicalExistingPath(String rawPath) {
        Path lexical = lexicalPath(rawPath);
        return realPath(lexical);
    }

    private static Path canonicalWritePath(String rawPath) {
        Path lexical = lexicalPath(rawPath);
        if (Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) return realPath(lexical);

        ArrayDeque<String> tail = new ArrayDeque<>();
        Path cursor = lexical;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            Path name = cursor.getFileName();
            if (name != null) tail.addFirst(name.toString());
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalArgumentException("cannot resolve filesystem permission path: " + rawPath);
        }

        Path resolved = realPath(cursor);
        for (String segment : tail) resolved = resolved.resolve(segment);
        return resolved.normalize();
    }

    private static Path lexicalPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("filesystem path cannot be blank");
        }
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            throw ioFailure("resolve", path, failure);
        }
    }

    private static void writeFully(FileChannel channel, byte[] bytes)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written <= 0) {
                throw new IOException(
                        "file channel made no progress while writing");
            }
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException(
                    "directory force is unsupported for " + directory,
                    unsupported);
        }
    }

    private static void requireSingleLink(Path path, String operation) {
        final Object raw;
        try {
            raw = Files.getAttribute(
                    path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            throw new IllegalStateException(
                    operation
                            + " requires Unix link-count metadata in M1; "
                            + "durable append is unsupported on this filesystem",
                    unsupported);
        } catch (IOException failure) {
            throw ioFailure("read hard-link count", path, failure);
        }
        if (!(raw instanceof Number number) || number.longValue() != 1L) {
            throw new SecurityException(
                    operation + " refuses hard-linked file: " + path);
        }
    }

    private static void maybeInjectDurabilityFaultForTest(
            String point,
            Path path) {
        if (DURABILITY_FAULT_FOR_TEST.compareAndSet(point, null)) {
            throw new DurabilityUncertainException(
                    "injected durability-uncertain outcome at " + point
                            + " for " + path,
                    new IOException("injected durability fault"));
        }
    }

    /**
     * The operation may have changed persistent state, but the runtime could
     * not prove the requested durability boundary. Callers must not ACK and
     * must recover/re-read storage before retrying.
     */
    public static final class DurabilityUncertainException
            extends IllegalStateException {
        public DurabilityUncertainException(
                String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void validateEnvName(String name) {
        if (name == null || name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("environment variable name is invalid");
        }
    }

    private static IllegalStateException ioFailure(String operation, Path path, IOException failure) {
        return new IllegalStateException(
                "filesystem " + operation + " failed for " + path + ": " + failure.getMessage(),
                failure);
    }
}

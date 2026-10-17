package dev.oreslang.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Trusted native host-I/O implementations for Oreslang.
 *
 * <p>Every operation checks permissions immediately before touching the host.
 * Guest code never receives Java IO/HTTP/socket objects.</p>
 */
public final class NativeIo {
    private NativeIo() { }

    // A null ProxySelector.of(...) address is invalid. Always use the direct
    // Java transport for numeric, policy-approved HTTP destinations; an
    // ambient proxy must not replace the already authorized remote address.
    private static final ProxySelector DIRECT_PROXY = new ProxySelector() {
        @Override public List<java.net.Proxy> select(URI uri) {
            return List.of(java.net.Proxy.NO_PROXY);
        }
        @Override public void connectFailed(
                URI uri, java.net.SocketAddress address, IOException failure) {
            // A direct connection has no proxy to mark failed.
        }
    };

    static ProxySelector directProxySelector() { return DIRECT_PROXY; }

    public static String readText(OresContext context, String rawPath) {
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.read_text");
        try {
            return readUtf8NoFollow(path);
        } catch (IOException failure) {
            throw ioFailure("read", path, failure);
        }
    }

    /**
     * Open the already-authorized canonical path using descriptor-relative
     * SecureDirectoryStream traversal from its filesystem root. Every
     * intermediate component and the final open use NOFOLLOW_LINKS, so a
     * swapped parent symlink cannot redirect a checked path to another tree.
     * Fail closed when a filesystem provider lacks SecureDirectoryStream.
     *
     * This is path-bound mediation, not a complete OS sandbox: rename of an
     * already opened directory, mount changes, hardlinks, and hostile filesystems
     * still require a distinct host isolation/handle ownership threat model.
     */
    static String readUtf8NoFollow(Path path) throws IOException {
        // Guest-controlled files previously streamed into an unbounded
        // StringBuilder. One large file per admitted I/O worker could exhaust
        // the host heap. Share the bounded source-reader byte limit, but keep
        // the final-component NOFOLLOW admission used for guest files.
        try (var channel = openCheckedFile(path, java.util.Set.of(
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = java.nio.channels.Channels.newInputStream(channel)) {
            byte[] bytes = stream.readNBytes(BoundedSourceReader.MAX_BYTES + 1);
            if (bytes.length > BoundedSourceReader.MAX_BYTES) {
                throw new IOException("Oreslang fs.read_text exceeds 16 MiB limit");
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }

    static void writeUtf8NoFollow(Path path, String value, boolean append) throws IOException {
        try (var channel = openCheckedFile(path, java.util.Set.of(
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS));
             var stream = java.nio.channels.Channels.newOutputStream(channel);
             var writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8.newEncoder())) {
            writer.write(value);
        }
    }

    /**
     * Open under a stable directory descriptor, never by a full pathname
     * reconstructed after scope authorization. The root directory is trusted
     * as the starting anchor; no ancestor is reopened by name after stepping
     * into its secure child stream.
     */
    @FunctionalInterface
    private interface SecureParentAction<T> {
        T apply(java.nio.file.SecureDirectoryStream<Path> parent, Path leaf) throws IOException;
    }

    private static <T> T withSecureParent(Path path, SecureParentAction<T> action)
            throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getFileName() == null || absolute.getRoot() == null)
            throw new IOException("cannot open filesystem root as a guest file");
        java.nio.file.DirectoryStream<Path> root =
                Files.newDirectoryStream(absolute.getRoot());
        if (!(root instanceof java.nio.file.SecureDirectoryStream<?>)) {
            root.close();
            throw new IOException("secure descriptor-relative file I/O unsupported on provider");
        }
        @SuppressWarnings("unchecked")
        java.nio.file.SecureDirectoryStream<Path> current =
                (java.nio.file.SecureDirectoryStream<Path>) root;
        try {
            int index = 0;
            for (Path segment : absolute) {
                if (++index == absolute.getNameCount()) break;
                java.nio.file.SecureDirectoryStream<Path> next =
                        current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return action.apply(current, absolute.getFileName());
        } finally {
            current.close();
        }
    }

    private static java.nio.channels.SeekableByteChannel openCheckedFile(
            Path path, java.util.Set<? extends java.nio.file.OpenOption> options)
            throws IOException {
        return withSecureParent(path, (parent, leaf) -> {
            if (options.contains(StandardOpenOption.CREATE)
                    || options.contains(StandardOpenOption.CREATE_NEW)) {
                return parent.newByteChannel(leaf, options,
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            }
            return parent.newByteChannel(leaf, options);
        });
    }

    static java.nio.file.attribute.BasicFileAttributes secureFileAttributes(Path path)
            throws IOException {
        return withSecureParent(path, (parent, leaf) -> {
            var attributes = parent.getFileAttributeView(
                    leaf, java.nio.file.attribute.BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes == null)
                throw new IOException("secure filesystem metadata unavailable");
            return attributes.readAttributes();
        });
    }

    static void secureAtomicWrite(Path target, String value) throws IOException {
        withSecureParent(target, (parent, leaf) -> {
            // CREATE_NEW and NOFOLLOW close temp-file name reuse and symlink
            // attacks. The rename is relative to the same opened directory.
            Path temporary = Path.of(".oreslang-atomic-" + java.util.UUID.randomUUID() + ".tmp");
            try {
                try (var channel = parent.newByteChannel(temporary,
                            java.util.Set.of(StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                     var stream = java.nio.channels.Channels.newOutputStream(channel);
                     var writer = new OutputStreamWriter(
                             stream, StandardCharsets.UTF_8.newEncoder())) {
                    writer.write(value);
                }
                // Unix SecureDirectoryStream uses descriptor-relative rename;
                // replacing the leaf entry never follows a swapped symlink.
                parent.move(temporary, parent, leaf);
            } finally {
                try { parent.deleteFile(temporary); }
                catch (java.nio.file.NoSuchFileException ignored) { }
            }
            return null;
        });
    }

    static List<String> secureListDirectory(Path path) throws IOException {
        return withSecureParent(path, (parent, leaf) -> {
            try (var entries = parent.newDirectoryStream(leaf, LinkOption.NOFOLLOW_LINKS)) {
                java.util.ArrayList<String> names = new java.util.ArrayList<>();
                for (Path item : entries) {
                    if (names.size() == 20000)
                        throw new IOException("fs.list_dir exceeds 20000 entries");
                    names.add(item.getFileName().toString());
                }
                names.sort(String::compareTo);
                return names;
            }
        });
    }

    static void secureRemove(Path path) throws IOException {
        withSecureParent(path, (parent, leaf) -> {
            var view = parent.getFileAttributeView(
                    leaf, java.nio.file.attribute.BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null)
                throw new IOException("secure filesystem metadata unavailable");
            var attributes = view.readAttributes();
            if (attributes.isDirectory()) parent.deleteDirectory(leaf);
            else parent.deleteFile(leaf);
            return null;
        });
    }

    public static boolean exists(OresContext context, String rawPath) {
        Path path = canonicalIfExisting(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.exists");
        try {
            secureFileAttributes(path);
            return true;
        } catch (java.nio.file.NoSuchFileException missing) {
            return false;
        } catch (IOException failure) {
            throw ioFailure("exists", path, failure);
        }
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
        try {
            return secureListDirectory(path);
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
        Path path = metadataTarget(context, rawPath, "fs.is_symlink");
        try { return secureFileAttributes(path).isSymbolicLink(); }
        catch (java.nio.file.NoSuchFileException missing) { return false; }
        catch (IOException failure) { throw ioFailure("is symlink", path, failure); }
    }

    public static boolean isDir(OresContext context, String rawPath) {
        Path path = metadataTarget(context, rawPath, "fs.is_dir");
        try { return secureFileAttributes(path).isDirectory(); }
        catch (java.nio.file.NoSuchFileException missing) { return false; }
        catch (IOException failure) { throw ioFailure("is directory", path, failure); }
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
        try {
            secureAtomicWrite(target, value);
        } catch (IOException failure) {
            throw ioFailure("atomic write", target, failure);
        }
    }

    public static void writeText(OresContext context, String rawPath, String value) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.write_text");
        try {
            writeUtf8NoFollow(path, value, false);
        } catch (IOException failure) {
            throw ioFailure("write", path, failure);
        }
    }

    public static void appendText(OresContext context, String rawPath, String value) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.append_text");
        try {
            writeUtf8NoFollow(path, value, true);
        } catch (IOException failure) {
            throw ioFailure("append", path, failure);
        }
    }

    public static void createDirectories(OresContext context, String rawPath) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.mkdir_all");
        try {
            // A Java pathname-based Files.createDirectories call can follow a
            // swapped ancestor after authorization. SecureDirectoryStream has
            // no mkdirat operation. Until an audited native dirfd-relative
            // adapter is available, only an existing verified directory is
            // an idempotent success; new directory creation fails closed.
            if (!secureFileAttributes(path).isDirectory())
                throw new IllegalArgumentException("fs.mkdir_all target is not a directory");
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new SecurityException(
                    "fs.mkdir_all creating new directories requires a verified mkdirat adapter", missing);
        } catch (IOException failure) {
            throw ioFailure("verify directory", path, failure);
        }
    }

    public static void remove(OresContext context, String rawPath) {
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.remove");
        try {
            secureRemove(path);
        } catch (IOException failure) {
            throw ioFailure("remove", path, failure);
        }
    }

    public static String envGet(OresContext context, String name) {
        validateEnvName(name);
        context.requirePermission(RuntimePermissions.Permission.ENV, name, "env.get");
        return System.getenv(name);
    }

    public static boolean envHas(OresContext context, String name) {
        validateEnvName(name);
        context.requirePermission(RuntimePermissions.Permission.ENV, name, "env.has");
        return System.getenv().containsKey(name);
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
        // HttpClient has no address-pinning API. Until HTTPS can preserve the
        // original TLS identity while pinning a checked socket address, only
        // canonical numeric IPv4 destinations are admitted. Never let HttpClient
        // resolve a DNS hostname independently of the permission decision.
        requirePinnedHttpLiteral(context.runtimePermissions(), endpoint);

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
                .proxy(directProxySelector()) // Do not let ambient proxies reroute a checked IP.
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        try (client) {
            HttpResponse<java.io.InputStream> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream bodyStream = response.body()) {
                return boundedHttpText(bodyStream);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("HTTP request failed for " + endpoint + ": " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted for " + endpoint, interrupted);
        }
    }

    // Enforce a strict response budget while reading the stream. The default
    // ofString()/ofByteArray() handlers buffer unbounded peer-controlled data.
    // This method remains host-only and never exposes the stream to guest code.
    static String boundedHttpText(java.io.InputStream body) throws IOException {
        final int maxBytes = 8 * 1024 * 1024;
        byte[] bytes = body.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes)
            throw new IOException("HTTP response exceeds 8 MiB limit");
        return new String(bytes, StandardCharsets.UTF_8);
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
        // Direct TCP bypasses any ambient SOCKS selector or JVM proxy settings.
        Socket socket = new Socket(java.net.Proxy.NO_PROXY);
        try {
            context.nativeIo().register(socket);
            // Resolve exactly once under the checked host policy; the chosen
            // InetSocketAddress contains InetAddress bytes, not a hostname.
            // Socket.connect(selected) therefore cannot re-resolve/rebind.
            InetSocketAddress selected = resolveAuthorizedAddress(
                    context.runtimePermissions(), host, port);
            context.requireCapability(IsolatePolicy.Capability.NETWORK, "network.connect");
            socket.connect(selected, 15_000);
            return new TcpConnection(context, endpoint, socket);
        } catch (Exception failure) {
            context.nativeIo().unregister(socket);
            try { socket.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof SecurityException denied) throw denied;
            throw new IllegalStateException("network connection failed", failure);
        }
    }

    // Package-private for loopback integration tests; callers must separately
    // enforce the effective actor/context capability at the I/O boundary.
    static InetSocketAddress resolveAuthorizedAddress(
            RuntimePermissions permissions, String host, int port) throws IOException {
        if (permissions == null || host == null || port < 1 || port > 65535)
            throw new IllegalArgumentException("invalid requested socket endpoint");
        String requested = host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
        if (!permissions.allows(RuntimePermissions.Permission.NET, requested))
            throw new SecurityException("requested network endpoint denied");

        // getAllByName may return both families, aliases and many answers.
        // Unsupported IPv6 and every denied numeric-IP candidate are skipped.
        // No host-name InetSocketAddress is ever passed to connect().
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length > 64)
            throw new SecurityException("DNS response exceeds address admission limit");
        for (InetAddress address : addresses) {
            if (!(address instanceof Inet4Address)) continue;
            try {
                // The same independently tested selected-IP policy helper
                // returns the exact InetAddress bytes later sent to connect.
                return VerifiedTcpSocket.checkedAddress(
                        permissions, requested, address, port);
            } catch (SecurityException denied) {
                // No retry is permitted for this numeric destination.
            }
        }
        throw new SecurityException("no resolved network address is authorized");
    }

    // A port-zero listener is not a request for an unrestricted fixed port.
    // Require port-independent host authority, no NET deny scopes (because
    // host aliases and the OS-selected port cannot be excluded otherwise),
    // and a checked concrete IPv4 bind address before reaching HttpServer.
    // Fixed-port listeners retain the normal exact-address admission path.
    static InetSocketAddress resolveAuthorizedListenerAddress(
            RuntimePermissions permissions, String host, int port) throws IOException {
        if (port != 0) return resolveAuthorizedAddress(permissions, host, port);
        if (permissions == null || host == null || host.isBlank())
            throw new IllegalArgumentException("invalid requested listener endpoint");

        String requested = host.toLowerCase(java.util.Locale.ROOT);
        if (!permissions.allows(RuntimePermissions.Permission.NET, requested))
            throw new SecurityException("ephemeral listener requires port-independent host permission");

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length > 64)
            throw new SecurityException("DNS response exceeds listener admission limit");
        for (InetAddress address : addresses) {
            // The all-interfaces address is not one selected network identity:
            // it would also bind IPs covered by other host-level denies.
            if (!(address instanceof Inet4Address) || address.isAnyLocalAddress()) continue;
            if (permissions.allowsEphemeralNetworkBind(requested, address.getHostAddress()))
                return new InetSocketAddress(address, 0);
        }
        throw new SecurityException("no resolved listener address is authorized for every port");
    }

    static void requirePinnedHttpLiteral(RuntimePermissions permissions, String endpoint) {
        // IPv4-only: the exact numeric endpoint is both the request target and
        // the selected address. Hostnames/IPv6 are denied, not passed to an
        // implicit resolver or an unaudited alternate HTTP transport.
        if (!permissions.allowsNetworkConnection(endpoint, endpoint))
            throw new SecurityException("HTTP requires an approved numeric IPv4 destination");
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

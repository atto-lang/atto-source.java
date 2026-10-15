package dev.oreslang.runtime;

import java.util.List;

/** Closed legacy ABI: no filesystem, stream, socket or Java HTTP implementation. */
public final class NativeIo {
    private NativeIo() { }
    private static UnsupportedOperationException unsupported(String operation) {
        return CoreIoPolicy.NATIVE_ASYNC_ONLY.blocking(operation);
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

    private static void validateEnvName(String name) {
        if (name == null || name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("environment variable name is invalid");
    }
    public static String readText(OresContext context, String path) { throw unsupported("readText"); }
    public static boolean exists(OresContext context, String path) { throw unsupported("exists"); }
    public static List<String> listDir(OresContext context, String path) { throw unsupported("listDir"); }
    public static boolean isSymlink(OresContext context, String path) { throw unsupported("isSymlink"); }
    public static boolean isDir(OresContext context, String path) { throw unsupported("isDir"); }
    public static void writeTextAtomic(OresContext context, String path, String value) { throw unsupported("writeTextAtomic"); }
    public static void writeText(OresContext context, String path, String value) { throw unsupported("writeText"); }
    public static void appendText(OresContext context, String path, String value) { throw unsupported("appendText"); }
    public static void createDirectories(OresContext context, String path) { throw unsupported("createDirectories"); }
    public static void remove(OresContext context, String path) { throw unsupported("remove"); }
    public static String httpGetText(OresContext context, String uri) { throw unsupported("httpGetText"); }
    public static String httpPostText(OresContext context, String uri, String body) { throw unsupported("httpPostText"); }
    public static TcpConnection connect(OresContext context, String host, int port) { throw unsupported("connect"); }
    public static final class TcpConnection implements AutoCloseable {
        private TcpConnection() { }
        public String readLine() { throw unsupported("socket.read_line"); }
        public void writeText(String value) { throw unsupported("socket.write_text"); }
        @Override public void close() { throw unsupported("socket.close"); }
    }
}

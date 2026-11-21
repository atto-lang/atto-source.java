package dev.oreslang.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JNI boundary for Oreslang networking.
 *
 * This class deliberately does not use java.net.Socket, ServerSocket,
 * SocketChannel, or java.net.http. All transport I/O crosses directly into the
 * platform socket API through liboresnet.
 *
 * The long values exposed here are opaque liboresnet capability handles, not
 * operating-system file-descriptor numbers. liboresnet resolves each handle
 * through a randomized slot registry and rejects stale/revoked handles.
 */
public final class NativeSocketBridge {
    private static final String LIBRARY = "oresnet";

    static {
        loadNativeLibrary();
    }

    private NativeSocketBridge() { }

    private static void loadNativeLibrary() {
        String explicit = System.getProperty("ores.net.native.path");
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }

        Path local = Path.of("target", "native", System.mapLibraryName(LIBRARY))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(local)) {
            System.load(local.toString());
            return;
        }

        System.loadLibrary(LIBRARY);
    }

    public static native long connect(String host, int port, int timeoutMillis) throws IOException;
    public static native long listen(String host, int port, int backlog, boolean reuseAddress) throws IOException;
    public static native long accept(long handle) throws IOException;

    public static native int read(long handle, byte[] bytes, int offset, int length) throws IOException;
    public static native int write(long handle, byte[] bytes, int offset, int length) throws IOException;
    public static native int available(long handle) throws IOException;

    public static native void shutdownInput(long handle) throws IOException;
    public static native void shutdownOutput(long handle) throws IOException;
    public static native void close(long handle) throws IOException;

    public static native String remoteAddress(long handle) throws IOException;
    public static native String localAddress(long handle) throws IOException;
    public static native int remotePort(long handle) throws IOException;
    public static native int localPort(long handle) throws IOException;
    public static native String[] resolveAll(String host) throws IOException;

    public static native void setTcpNoDelay(long handle, boolean enabled) throws IOException;
    public static native boolean getTcpNoDelay(long handle) throws IOException;
    public static native void setKeepAlive(long handle, boolean enabled) throws IOException;
    public static native boolean getKeepAlive(long handle) throws IOException;
    public static native void setReuseAddress(long handle, boolean enabled) throws IOException;
    public static native boolean getReuseAddress(long handle) throws IOException;
    public static native void setReceiveBufferSize(long handle, int bytes) throws IOException;
    public static native int getReceiveBufferSize(long handle) throws IOException;
    public static native void setSendBufferSize(long handle, int bytes) throws IOException;
    public static native int getSendBufferSize(long handle) throws IOException;
    public static native void setSoTimeout(long handle, int timeoutMillis) throws IOException;
    public static native int getSoTimeout(long handle) throws IOException;
}

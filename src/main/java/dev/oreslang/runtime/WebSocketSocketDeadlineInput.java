package dev.oreslang.runtime;

import java.io.FilterInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Absolute read deadline over one accepted TCP socket frame/request head.
 *
 * <p>Socket.setSoTimeout is otherwise an idle timeout that a byte-at-a-time
 * peer can postpone indefinitely. Both single-byte and bulk reads must reset
 * the SO_TIMEOUT to the time remaining on the *same* frame deadline. A read
 * that completes after the absolute deadline also fails closed. This helper
 * never owns or closes the socket: the native-I/O owner performs retirement.</p>
 */
final class WebSocketSocketDeadlineInput extends FilterInputStream {
    private final Socket socket;
    private final long deadline;

    WebSocketSocketDeadlineInput(Socket socket, int timeoutMillis) throws IOException {
        super(Objects.requireNonNull(socket, "socket").getInputStream());
        if (timeoutMillis < 1 || timeoutMillis > 30_000) {
            throw new IllegalArgumentException("invalid absolute WebSocket read deadline");
        }
        this.socket = socket;
        this.deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private void beforeRead() throws IOException {
        long left = deadline - System.nanoTime();
        if (left <= 0L) {
            throw new SocketTimeoutException("WebSocket absolute read deadline expired");
        }
        // Integer millis are rounded UP, so a sub-millisecond remainder
        // does not produce SO_TIMEOUT=0 (which means unbounded).
        long millis = Math.max(1L, (left + 999_999L) / 1_000_000L);
        socket.setSoTimeout((int) Math.min(millis, 30_000L));
    }

    private void afterRead() throws SocketTimeoutException {
        if (System.nanoTime() > deadline) {
            throw new SocketTimeoutException("WebSocket absolute read deadline expired");
        }
    }

    @Override public int read() throws IOException {
        beforeRead();
        int result = in.read();
        afterRead();
        return result;
    }

    @Override public int read(byte[] bytes, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, bytes.length);
        if (count == 0) return 0;
        beforeRead();
        int result = in.read(bytes, offset, count);
        afterRead();
        return result;
    }

    // No close() override: FilterInputStream.close() would close the underlying
    // socket. Callers must NOT use try-with-resources on this borrowed view.
}

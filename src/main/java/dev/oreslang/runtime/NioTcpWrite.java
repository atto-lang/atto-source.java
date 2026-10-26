package dev.oreslang.runtime;

import java.nio.channels.SocketChannel;
import java.util.concurrent.RejectedExecutionException;

final class NioTcpWrite {
    private NioTcpWrite() { }

    static OresFuture<Integer> start(
            NioReadinessCore core, SocketChannel channel, byte[] bytes) {
        if (core == null || channel == null) throw new NullPointerException();
        if (bytes == null || bytes.length > 65536)
            return OresFuture.failed(new IllegalArgumentException("invalid TCP write bound"));
        return OresFuture.failed(new RejectedExecutionException(
                "nonblocking TCP write is fail-closed until current-main pump reconciliation lands"));
    }
}

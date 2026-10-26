package dev.oreslang.runtime;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.RejectedExecutionException;

final class NioTcpAccept {
    private NioTcpAccept() { }

    static OresFuture<SocketChannel> start(
            NioReadinessCore core, ServerSocketChannel listener) {
        if (core == null || listener == null) throw new NullPointerException();
        return OresFuture.failed(new RejectedExecutionException(
                "nonblocking TCP accept is fail-closed until current-main pump reconciliation lands"));
    }
}

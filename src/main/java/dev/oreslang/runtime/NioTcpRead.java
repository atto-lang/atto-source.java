package dev.oreslang.runtime;

import java.nio.channels.SocketChannel;
import java.util.concurrent.RejectedExecutionException;

final class NioTcpRead {
    private NioTcpRead() { }

    static OresFuture<byte[]> start(
            NioReadinessCore core, SocketChannel channel, int maximum) {
        if (core == null || channel == null) throw new NullPointerException();
        if (maximum < 1 || maximum > 65536)
            return OresFuture.failed(new IllegalArgumentException("invalid TCP read bound"));
        return OresFuture.failed(new RejectedExecutionException(
                "nonblocking TCP read is fail-closed until current-main pump reconciliation lands"));
    }
}

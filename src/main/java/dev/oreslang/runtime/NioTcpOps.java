package dev.oreslang.runtime;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

final class NioTcpOps {
    private NioTcpOps() { }

    static OresFuture<SocketChannel> accept(
            NioReadinessCore core, ServerSocketChannel listener) {
        return NioTcpAccept.start(core, listener);
    }

    static OresFuture<byte[]> read(
            NioReadinessCore core, SocketChannel channel, int maximum) {
        return NioTcpRead.start(core, channel, maximum);
    }

    static OresFuture<Integer> write(
            NioReadinessCore core, SocketChannel channel, byte[] bytes) {
        return NioTcpWrite.start(core, channel, bytes);
    }
}

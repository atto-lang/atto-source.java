package dev.oreslang.runtime;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.IdentityHashMap;

final class NioLeaseBook {
    private final IdentityHashMap<ServerSocketChannel, OresFuture<SocketChannel>> accepts =
            new IdentityHashMap<>();
    private final IdentityHashMap<SocketChannel, OresFuture<byte[]>> reads =
            new IdentityHashMap<>();
    private final IdentityHashMap<SocketChannel, OresFuture<Integer>> writes =
            new IdentityHashMap<>();

    synchronized boolean reserveAccept(
            ServerSocketChannel channel, OresFuture<SocketChannel> future, boolean closed) {
        if (closed || !channel.isOpen() || accepts.containsKey(channel)) return false;
        accepts.put(channel, future);
        return true;
    }

    synchronized void releaseAccept(
            ServerSocketChannel channel, OresFuture<SocketChannel> future) {
        if (accepts.get(channel) == future) accepts.remove(channel);
    }

    synchronized boolean reserveRead(
            SocketChannel channel, OresFuture<byte[]> future, boolean closed) {
        if (closed || !channel.isOpen()
                || reads.containsKey(channel) || writes.containsKey(channel)) return false;
        reads.put(channel, future);
        return true;
    }

    synchronized void releaseRead(SocketChannel channel, OresFuture<byte[]> future) {
        if (reads.get(channel) == future) reads.remove(channel);
    }

    synchronized boolean reserveWrite(
            SocketChannel channel, OresFuture<Integer> future, boolean closed) {
        if (closed || !channel.isOpen()
                || reads.containsKey(channel) || writes.containsKey(channel)) return false;
        writes.put(channel, future);
        return true;
    }

    synchronized void releaseWrite(SocketChannel channel, OresFuture<Integer> future) {
        if (writes.get(channel) == future) writes.remove(channel);
    }

    synchronized boolean empty() {
        return accepts.isEmpty() && reads.isEmpty() && writes.isEmpty();
    }

    synchronized int accepts() { return accepts.size(); }
    synchronized int reads() { return reads.size(); }
    synchronized int writes() { return writes.size(); }
}

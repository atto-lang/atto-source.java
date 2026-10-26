package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class NioSelectorReactor implements Runnable {
    static final class Watch {
        private final NioReadinessCore owner;
        private final SelectableChannel channel;
        private final int interests;
        private final NioSelectorReactor reactor;
        private final AtomicBoolean retired = new AtomicBoolean();
        private final OresFuture<Integer> future;
        private SelectionKey key;

        Watch(NioReadinessCore owner, SelectableChannel channel,
                int interests, NioSelectorReactor reactor) {
            this.owner = owner;
            this.channel = channel;
            this.interests = interests;
            this.reactor = reactor;
            this.future = new OresFuture<>(() -> true, () -> {
                if (retired.get() || reactor.stopping.get()) return;
                reactor.cancellations.add(this);
                if (retired.get() || reactor.stopping.get()) {
                    reactor.cancellations.remove(this);
                    return;
                }
                reactor.selector.wakeup();
            });
        }

        SelectableChannel channel() { return channel; }
        OresFuture<Integer> future() { return future; }
        boolean claimRetired() { return retired.compareAndSet(false, true); }
    }

    private final NioReadinessCore owner;
    private final Selector selector;
    private final ArrayBlockingQueue<Watch> commands;
    private final ConcurrentHashMap<Watch, Boolean> admitted = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Watch> cancellations = new ConcurrentLinkedQueue<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Thread thread;

    NioSelectorReactor(NioReadinessCore owner, int index, int commandCapacity)
            throws IOException {
        this.owner = owner;
        selector = Selector.open();
        commands = new ArrayBlockingQueue<>(commandCapacity);
        thread = Thread.ofPlatform().daemon(true)
                .name("ores-nio-reactor-" + index)
                .unstarted(() -> OresVM.runAsNioWorker(() -> {
                    NioReadinessGroup.enterReactor();
                    try { run(); }
                    finally { NioReadinessGroup.leaveReactor(); }
                }));
    }

    int queueSize() { return commands.size(); }
    boolean stopping() { return stopping.get(); }

    boolean admit(Watch watch) {
        if (stopping.get()) return false;
        admitted.put(watch, Boolean.TRUE);
        if (!commands.offer(watch)) {
            admitted.remove(watch);
            return false;
        }
        selector.wakeup();
        return true;
    }

    void start() { thread.start(); }

    void abortUnstarted() {
        if (thread.getState() != Thread.State.NEW)
            throw new IllegalStateException("reactor was already started");
        stopping.set(true);
        try { selector.close(); } catch (IOException ignored) { }
        stopped.countDown();
    }

    void requestStop() {
        stopping.set(true);
        selector.wakeup();
    }

    boolean awaitStopped(long time, TimeUnit unit) throws InterruptedException {
        return stopped.await(time, unit);
    }

    private void retire(Watch watch, Integer events, Throwable error) {
        if (!watch.claimRetired()) return;
        if (watch.key != null) watch.key.cancel();
        admitted.remove(watch);
        owner.retire(watch, events, error);
    }

    @Override public void run() {
        Throwable terminal = null;
        try {
            while (!stopping.get()) {
                Watch watch;
                int work = 0;
                boolean sweptCancelledKeys = false;
                while (work++ < 128 && (watch = commands.poll()) != null) {
                    if (!sweptCancelledKeys) {
                        selector.selectNow();
                        sweptCancelledKeys = true;
                    }
                    if (watch.future.isCancelled() || owner.closed() || stopping.get()) {
                        retire(watch, null, new IOException("NIO watch terminated"));
                        continue;
                    }
                    try {
                        watch.key = watch.channel.register(
                                selector, watch.interests, watch);
                        if (watch.future.isCancelled())
                            retire(watch, null, new IOException("NIO watch cancelled"));
                    } catch (IOException | RuntimeException failure) {
                        retire(watch, null, failure);
                    }
                }
                if (stopping.get()) break;

                int cancellationQuantum = 0;
                while (cancellationQuantum++ < 128
                        && (watch = cancellations.poll()) != null) {
                    if (watch.future.isCancelled())
                        retire(watch, null, new IOException("NIO watch cancelled"));
                }

                if (commands.isEmpty() && cancellations.isEmpty()
                        && selector.selectedKeys().isEmpty())
                    selector.select(1000);
                else
                    selector.selectNow();

                Iterator<SelectionKey> selected = selector.selectedKeys().iterator();
                int readyQuantum = 0;
                while (readyQuantum++ < 128 && selected.hasNext()) {
                    SelectionKey key = selected.next();
                    selected.remove();
                    Watch ready = (Watch) key.attachment();
                    if (ready == null) continue;
                    if (!key.isValid())
                        retire(ready, null, new CancelledKeyException());
                    else
                        retire(ready, key.readyOps(), null);
                }
            }
        } catch (Throwable failure) {
            terminal = failure;
        } finally {
            stopping.set(true);
            Throwable reason = terminal == null
                    ? new IOException("NIO reactor stopped") : terminal;
            for (Watch watch : admitted.keySet())
                retire(watch, null, reason);
            commands.clear();
            cancellations.clear();
            try { selector.close(); } catch (IOException ignored) { }
            owner.reactorStopped();
            stopped.countDown();
        }

        if (terminal instanceof VirtualMachineError fatal) throw fatal;
        if (terminal instanceof ThreadDeath fatal) throw fatal;
    }
}

package dev.oreslang;

import dev.oreslang.runtime.CriticalSectionGate;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable host-native smoke tests, independent of GraalVM dependencies. */
public final class CriticalSectionGateStandaloneTest {
    static int assertions;
    static void check(boolean yes, String msg) {
        if (!yes) throw new AssertionError(msg);
        assertions++;
    }
    static void cancelled(CriticalSectionGate.Ticket ticket) {
        try { ticket.whenReady().toCompletableFuture().join(); throw new AssertionError("not cancelled"); }
        catch (CancellationException | java.util.concurrent.CompletionException expected) {
            check(true, "cancelled");
        }
    }
    static void expect(Class<? extends Throwable> type, Runnable call) {
        try { call.run(); throw new AssertionError("no exception: " + type.getName()); }
        catch (Throwable failure) {
            if (failure instanceof AssertionError) throw (AssertionError) failure;
            check(type.isInstance(failure), "wrong error " + failure);
        }
    }

    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(17,2);
            Object first = new Object(), second = new Object(), third = new Object();
            CriticalSectionGate.Ticket owner = gate.tryEnter(first,17).orElseThrow();
            check(gate.tryEnter(new Object(),17).isEmpty(), "busy try-enter queued");
            check(gate.queued() == 0,"try-enter grew queue");
            var w2 = gate.enter(second,17);
            var w3 = gate.enter(third,17);
            check(gate.queued() == 2,"FIFO queue count");
            expect(java.util.concurrent.RejectedExecutionException.class, () -> gate.enter(new Object(),17));
            expect(IllegalStateException.class, () -> gate.enter(second,17));
            expect(IllegalStateException.class, () -> gate.tryEnter(first,17));
            check(owner.execute(() -> 42) == 42,"owner body result");
            check(w2.whenReady().toCompletableFuture().get(1,TimeUnit.SECONDS) == w2,"first FIFO");
            w2.execute(() -> {});
            check(w3.whenReady().toCompletableFuture().get(1,TimeUnit.SECONDS) == w3,"second FIFO");
            w3.execute(() -> {});
            check(gate.queued() == 0,"queue drained");
            expect(IllegalStateException.class, () -> owner.execute(() -> {}));
            expect(IllegalStateException.class, () -> gate.enter(new Object(),18));
            var active = gate.enter(new Object(),17);
            gate.retire();
            check(gate.quiescent(),"idle retire quiescent");
            expect(CancellationException.class, () -> active.execute(() -> {}));
            expect(java.util.concurrent.RejectedExecutionException.class, () -> gate.enter(new Object(),17));
            check(!active.cancel(),"cancelled by retirement");
        }
        // A completed grant stage is an observation, not a capability to
        // execute after cancellation or generation retirement.
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(50,2);
            var granted = gate.enter(new Object(),50);
            check(granted.whenReady().toCompletableFuture().join() == granted,
                    "granted stage completed");
            check(granted.cancel(), "cancel already-granted owner");
            expect(CancellationException.class, () -> granted.execute(() -> {}));
            check(gate.queued() == 0, "cancelled grant retained a waiter");
            var old = gate.enter(new Object(),50);
            gate.retire();
            expect(CancellationException.class, () -> old.execute(() -> {}));
            check(gate.quiescent(), "pre-entry retirement did not quiesce");
        }
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(200,4);
            var owner = gate.enter(new Object(),200);
            var a = gate.enter(new Object(),200);
            var b = gate.enter(new Object(),200);
            var c = gate.enter(new Object(),200);
            check(b.cancel(),"cancel queued");
            cancelled(b);
            check(gate.queued()==2,"cancelled waiter capacity released");
            var d = gate.enter(new Object(),200);
            check(a.cancel(),"cancel second queued");
            cancelled(a);
            owner.execute(() -> {});
            check(c.whenReady().toCompletableFuture().get(1,TimeUnit.SECONDS)==c,"FIFO after cancellation");
            c.execute(() -> {});
            check(d.whenReady().toCompletableFuture().get(1,TimeUnit.SECONDS)==d,"FIFO capacity reuse");
            d.execute(() -> {});
            gate.retire();
            check(gate.quiescent(),"retirement cleanup");
        }
        // Cancellation and physical callback admission must select exactly
        // one winner even when both callers begin on separate carriers.
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(61,2);
            var ticket = gate.enter(new Object(),61);
            CountDownLatch race = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            AtomicInteger denied = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread executing = Thread.ofPlatform().start(() -> {
                try {
                    race.await();
                    ticket.execute(callbacks::incrementAndGet);
                } catch (CancellationException expected) {
                    denied.incrementAndGet();
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                }
            });
            Thread cancelling = Thread.ofPlatform().start(() -> {
                try {
                    race.await();
                    if (ticket.cancel()) cancelled.incrementAndGet();
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                }
            });
            race.countDown();
            executing.join();
            cancelling.join();
            check(failure.get() == null,"unexpected admission/cancellation race error");
            check(callbacks.get() + denied.get() == 1,"callback did not settle exactly once");
            check((cancelled.get() == 1 && callbacks.get() == 0 && denied.get() == 1)
                    || (cancelled.get() == 0 && callbacks.get() == 1 && denied.get() == 0),
                    "cancelled ticket executed protected callback");
            gate.retire();
            check(gate.quiescent(),"cancel-vs-execute race did not reclaim lease");
        }
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(37,2);
            var owner=gate.enter(new Object(),37);
            var waiting=gate.enter(new Object(),37);
            CountDownLatch inside=new CountDownLatch(1),done=new CountDownLatch(1);
            AtomicInteger result=new AtomicInteger();
            Thread t=Thread.ofPlatform().start(() -> owner.execute(() -> {
                inside.countDown();
                try { done.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                result.incrementAndGet();
            }));
            check(inside.await(2,TimeUnit.SECONDS),"physical entry observed");
            gate.retire();
            check(!gate.quiescent(),"retire did not free active body");
            check(gate.queued()==0,"retire cleared pending");
            cancelled(waiting);
            check(!owner.cancel(),"cannot reclaim active body");
            check(owner.cancellationRequested(),"running body marked cancel requested");
            done.countDown(); t.join();
            check(result.get()==1,"body finished exactly once");
            check(gate.quiescent(),"quiescent after physical body");
        }
        System.out.println("PASS host Java CriticalSectionGate assertions=" + assertions);
    }
}

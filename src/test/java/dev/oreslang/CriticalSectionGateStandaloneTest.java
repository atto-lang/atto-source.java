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
                    ticket.execute(() -> { callbacks.incrementAndGet(); });
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
        // Exactly one scheduler is permitted to own delivery of a logical
        // continuation, even if registration races on separate carriers.
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(91,2);
            var ticket = gate.enter(new Object(),91);
            java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
            var granted = ticket.whenReadyOn(queue::addLast);
            expect(IllegalStateException.class, () -> ticket.whenReadyOn(queue::addLast));
            check(queue.size()==1, "duplicate subscription queued extra continuation");
            queue.removeFirst().run();
            check(granted.toCompletableFuture().join()==ticket, "single scheduler lost ticket");
            ticket.execute(() -> {});
            gate.retire();
            check(gate.quiescent(), "duplicate registration leaked lease");
        }
        // Race distinct scheduler subscriptions on two carriers. Only one
        // registration may survive; no duplicate logical resume may escape.
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(92,2);
            var ticket = gate.enter(new Object(),92);
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.ConcurrentLinkedQueue<Runnable> deliveries =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger denied = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletionStage<
                    CriticalSectionGate.Ticket>> granted = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> error =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Runnable attempt = () -> {
                try {
                    start.await();
                    var stage = ticket.whenReadyOn(deliveries::add);
                    granted.set(stage);
                    accepted.incrementAndGet();
                } catch (IllegalStateException expected) {
                    denied.incrementAndGet();
                } catch (Throwable unexpected) {
                    error.compareAndSet(null, unexpected);
                }
            };
            Thread a = Thread.ofPlatform().start(attempt);
            Thread b = Thread.ofPlatform().start(attempt);
            start.countDown();
            a.join(); b.join();
            check(error.get() == null, "racing dispatch registration failed unexpectedly");
            check(accepted.get() == 1 && denied.get() == 1,
                    "racing subscriptions did not select exactly one scheduler");
            check(deliveries.size()==1, "multiple deliveries queued for one logical turn");
            deliveries.remove().run();
            check(granted.get().toCompletableFuture().join() == ticket,
                    "single racing subscription lost grant");
            ticket.execute(() -> {});
            gate.retire();
            check(gate.quiescent(), "racing dispatch leaked physical permit");
        }
        // A scheduler-owned executor, not the releasing carrier, must run
        // guest continuation callbacks. Rejected dispatch revokes its permit.
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate gate = new CriticalSectionGate(81,2);
            var owner = gate.enter(new Object(),81);
            var waiting = gate.enter(new Object(),81);
            java.util.ArrayDeque<Runnable> controlLane = new java.util.ArrayDeque<>();
            var stage = waiting.whenReadyOn(controlLane::addLast);
            AtomicInteger callback = new AtomicInteger();
            stage.thenAccept(ticket -> callback.incrementAndGet());
            owner.execute(() -> {});
            check(controlLane.size() == 1,"scheduler handoff was not enqueued");
            check(callback.get() == 0,"guest continuation executed inline on release");
            controlLane.removeFirst().run();
            check(stage.toCompletableFuture().join() == waiting,"resume lost granted ticket");
            check(callback.get() == 1,"queued continuation was not dispatched");
            waiting.execute(() -> {});
            gate.retire();
            check(gate.quiescent(),"scheduler handoff leaked owner");

            // Inline executors violate scheduler/carrier isolation. Fail
            // closed and reclaim the granted permit instead of running guest
            // observers inside the completing control-lane callback.
            CriticalSectionGate inlineGate = new CriticalSectionGate(83,1);
            var inlineTicket = inlineGate.enter(new Object(),83);
            var inlineStage = inlineTicket.whenReadyOn(Runnable::run);
            try {
                inlineStage.toCompletableFuture().join();
                throw new AssertionError("inline scheduler unexpectedly accepted");
            } catch (java.util.concurrent.CompletionException expected) {
                check(expected.getCause() instanceof java.util.concurrent.RejectedExecutionException,
                        "inline scheduler did not fail closed");
            }
            var recovered = inlineGate.tryEnter(new Object(),83);
            check(recovered.isPresent(),"inline scheduler failure leaked admission");
            recovered.orElseThrow().execute(() -> {});
            inlineGate.retire();
            check(inlineGate.quiescent(),"inline scheduling left live permit");

            // Cancelled admission is also routed to a control-lane task:
            // an exceptional completion must not invoke guest observers on
            // the thread which cancelled or retired a waiter.
            CriticalSectionGate cancelledGate = new CriticalSectionGate(84,2);
            var cancelledOwner = cancelledGate.enter(new Object(),84);
            var pending = cancelledGate.enter(new Object(),84);
            java.util.ArrayDeque<Runnable> cancelledLane = new java.util.ArrayDeque<>();
            var cancelledStage = pending.whenReadyOn(cancelledLane::addLast);
            check(pending.cancel(), "queued cancellation did not succeed");
            check(cancelledLane.size() == 1, "cancelled continuation not enqueued");
            check(!cancelledStage.toCompletableFuture().isDone(),
                    "cancelled continuation completed inline on cancel lane");
            cancelledLane.removeFirst().run();
            try {
                cancelledStage.toCompletableFuture().join();
                throw new AssertionError("cancellation did not fail scheduler stage");
            } catch (CancellationException | java.util.concurrent.CompletionException expected) {
                check(true, "cancelled scheduler notification");
            }
            cancelledOwner.execute(() -> {});
            cancelledGate.retire();
            check(cancelledGate.quiescent(),"cancelled scheduler delivery leaked lease");

            CriticalSectionGate rejectGate = new CriticalSectionGate(82,2);
            var rejectOwner = rejectGate.enter(new Object(),82);
            var rejected = rejectGate.enter(new Object(),82);
            var successor = rejectGate.enter(new Object(),82);
            var failureStage = rejected.whenReadyOn(task -> {
                throw new java.util.concurrent.RejectedExecutionException("scheduler closed");
            });
            rejectOwner.execute(() -> {});
            try {
                failureStage.toCompletableFuture().join();
                throw new AssertionError("rejected executor did not fail stage");
            } catch (java.util.concurrent.CompletionException expected) {
                check(expected.getCause() instanceof java.util.concurrent.RejectedExecutionException,
                        "rejected executor did not report dispatch failure");
            }
            check(successor.whenReady().toCompletableFuture().join() == successor,
                    "rejected scheduler delivery stranded FIFO successor");
            successor.execute(() -> {});
            rejectGate.retire();
            check(rejectGate.quiescent(),"rejected scheduler dispatch leaked gate");
        }
        // A grant queued to the trusted scheduler is not guest execution
        // authority. Retirement/cancellation before delivery must fail closed.
        for (int round = 0; round < 300; round++) {
            CriticalSectionGate retiredGate = new CriticalSectionGate(83,2);
            var retiredOwner = retiredGate.enter(new Object(),83);
            var stale = retiredGate.enter(new Object(),83);
            java.util.ArrayDeque<Runnable> retireLane = new java.util.ArrayDeque<>();
            var staleStage = stale.whenReadyOn(retireLane::addLast);
            AtomicInteger staleCallbacks = new AtomicInteger();
            staleStage.thenAccept(ticket -> staleCallbacks.incrementAndGet());
            retiredOwner.execute(() -> {});
            check(retireLane.size()==1,"stale scheduler dispatch was not queued");
            retiredGate.retire();
            check(retiredGate.quiescent(),"retirement did not revoke granted pre-entry ticket");
            retireLane.removeFirst().run();
            try {
                staleStage.toCompletableFuture().join();
                throw new AssertionError("stale scheduler delivery admitted after retirement");
            } catch (CancellationException | java.util.concurrent.CompletionException expected) {
                check(true,"retired scheduled continuation denied");
            }
            check(staleCallbacks.get()==0,"stale scheduler callback ran after retirement");

            CriticalSectionGate cancelledGate = new CriticalSectionGate(84,2);
            var cancelOwner = cancelledGate.enter(new Object(),84);
            var cancelledBeforeDispatch = cancelledGate.enter(new Object(),84);
            var successor = cancelledGate.enter(new Object(),84);
            java.util.ArrayDeque<Runnable> cancelLane = new java.util.ArrayDeque<>();
            var cancelledStage = cancelledBeforeDispatch.whenReadyOn(cancelLane::addLast);
            AtomicInteger cancelledCallbacks = new AtomicInteger();
            cancelledStage.thenAccept(ticket -> cancelledCallbacks.incrementAndGet());
            cancelOwner.execute(() -> {});
            check(cancelLane.size()==1,"cancellation scheduler dispatch was not queued");
            check(cancelledBeforeDispatch.cancel(),"cancel before delivery did not revoke ticket");
            check(successor.whenReady().toCompletableFuture().join()==successor,
                    "cancel before delivery stranded FIFO successor");
            cancelLane.removeFirst().run();
            try {
                cancelledStage.toCompletableFuture().join();
                throw new AssertionError("cancelled scheduler delivery admitted stale ticket");
            } catch (CancellationException | java.util.concurrent.CompletionException expected) {
                check(true,"cancelled scheduled continuation denied");
            }
            check(cancelledCallbacks.get()==0,"cancelled scheduler callback ran");
            successor.execute(() -> {});
            cancelledGate.retire();
            check(cancelledGate.quiescent(),"cancel before dispatch leaked critical owner");
        }
        // Reject a deep bounded FIFO. Each rejection cancels the current
        // owner and wakes the next waiter; all notifications must be iterated,
        // not recursively nested on the original release/CONTROL carrier.
        {
            CriticalSectionGate gate = new CriticalSectionGate(90,4096);
            var owner = gate.enter(new Object(),90);
            java.util.ArrayList<java.util.concurrent.CompletionStage<
                    CriticalSectionGate.Ticket>> failures = new java.util.ArrayList<>();
            for (int i = 0; i < 2048; i++) {
                var ticket = gate.enter(new Object(),90);
                failures.add(ticket.whenReadyOn(command -> {
                    throw new java.util.concurrent.RejectedExecutionException("scheduler shutdown");
                }));
            }
            check(gate.queued()==2048,"deep FIFO queue size");
            owner.execute(() -> {});
            check(gate.queued()==0,"rejected FIFO chain left pending waiters");
            for (var failure : failures) {
                check(failure.toCompletableFuture().isCompletedExceptionally(),
                        "one rejected FIFO ticket never settled");
            }
            gate.retire();
            check(gate.quiescent(),"deep reject chain leaked physical ownership");
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

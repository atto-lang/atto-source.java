package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone checks: JDK-only host conformance (also wrapped by JUnit). */
public final class GenerationalReplyRegistryCheck {
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("generational reply contract failed");
    }
    private static void deny(Runnable r) {
        try {
            r.run();
            throw new AssertionError("invalid call unexpectedly accepted");
        } catch (SecurityException | IllegalArgumentException | IllegalStateException expected) {
            // expected
        }
    }
    public static void main(String[] args) throws Exception {
        var registry = new GenerationalReplyRegistry(2048);
        var actor = registry.newActorEndpoint();
        var child = registry.newActorEndpoint();
        var supervisor = registry.newSupervisorApplicationEndpoint();
        check(supervisor.role() == GenerationalReplyRegistry.Role.SUPERVISOR_APPLICATION);

        // Actor -> supervisor and supervisor -> actor: same typed primitive.
        var policy = registry.reserve(actor, supervisor, String.class);
        var stats = registry.reserve(supervisor, actor, Integer.class);
        check(policy.reply(supervisor, "active"));
        check(stats.reply(actor, 42));
        check(policy.result().toCompletableFuture().join().equals("active"));
        check(stats.result().toCompletableFuture().join() == 42);

        // Cancel, slot reuse and ABA protection.
        var stale = registry.reserve(actor, child, String.class);
        long oldHandle = stale.localHandle();
        check(stale.cancel(actor));
        var next = registry.reserve(actor, child, String.class);
        check(oldHandle != next.localHandle());
        check(!stale.reply(child, "late"));
        deny(() -> next.reply(supervisor, "forged"));
        deny(() -> next.cancel(child));
        check(next.reply(child, "new"));
        check(next.result().toCompletableFuture().join().equals("new"));
        deny(() -> registry.reserve(actor, actor, String.class));
        deny(() -> registry.reserve(actor, child, Void.class));

        // Bounded capacity with reverse-order replies.
        var pending = new ArrayList<GenerationalReplyRegistry.Ticket<Integer>>();
        for (int i = 0; i < 2048; ++i) {
            pending.add(registry.reserve(actor, child, Integer.class));
        }
        deny(() -> registry.reserve(actor, child, Integer.class));
        for (int i = 2047; i >= 0; --i) check(pending.get(i).reply(child, i));
        for (int i = 0; i < 2048; ++i) {
            check(pending.get(i).result().toCompletableFuture().join() == i);
        }
        check(registry.pendingCount() == 0);

        // Exactly one of concurrent reply vs cancellation wins.
        var wins = new AtomicInteger();
        for (int i = 0; i < 120; ++i) {
            var call = registry.reserve(actor, child, Integer.class);
            var gate = new CountDownLatch(1);
            Thread responder = new Thread(() -> {
                try { gate.await(); if (call.reply(child, 1)) wins.incrementAndGet(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            Thread canceler = new Thread(() -> {
                try { gate.await(); if (call.cancel(actor)) wins.incrementAndGet(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            responder.start();
            canceler.start();
            gate.countDown();
            responder.join();
            canceler.join();
            check(registry.pendingCount() == 0);
        }
        check(wins.get() == 120);

        var abandoned = registry.reserve(actor, child, Integer.class);
        registry.retire(child);
        check(!abandoned.reply(child, 10));
        check(abandoned.result().toCompletableFuture().isCompletedExceptionally());
        deny(() -> registry.reserve(actor, child, String.class));
        var reincarnated = registry.newActorEndpoint();
        var fresh = registry.reserve(actor, reincarnated, String.class);
        deny(() -> fresh.reply(child, "stale incarnation"));
        check(fresh.timeout(actor));
        check(fresh.result().toCompletableFuture().isCompletedExceptionally());

        var forgotten = registry.reserve(supervisor, reincarnated, Integer.class);
        registry.close();
        check(forgotten.result().toCompletableFuture().isCompletedExceptionally());
        check(registry.pendingCount() == 0);
        deny(() -> registry.reserve(actor, reincarnated, String.class));
    }
}

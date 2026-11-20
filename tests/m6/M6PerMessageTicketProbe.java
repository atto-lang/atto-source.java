// Host-side M6 exact-message receipt proof. No guest ABI or canonical proxy
// compiler support is asserted by this probe. The private runtime ticket is
// not a serialized actor ref, reply slot, or guest-provided receipt.
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class M6PerMessageTicketProbe {
    private M6PerMessageTicketProbe() {}
    private static void require(boolean truth, String why) {
        if (!truth) throw new AssertionError(why);
    }
    private static AttoM6Runtime.Ticket accept(AttoM6Runtime runtime,
            AttoM6Runtime.ActorRef ref, long value) {
        var a = runtime.try_send_ticket(ref, value);
        require(a.admission() == AttoM6Runtime.Admission.ACCEPTED && a.ticket() != null,
            "no issued receipt for admitted message: " + a.admission());
        return a.ticket();
    }
    private static AttoM6Runtime.TicketSnapshot await(AttoM6Runtime runtime,
            AttoM6Runtime.Ticket ticket) throws InterruptedException {
        return runtime.await_ticket(ticket, AttoM6Runtime.TicketState.PROCESSED, 10_000);
    }
    private static void separate_actors_and_mailbox_capacity() throws Exception {
        try (var runtime = new AttoM6Runtime(2, 5)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var slow = runtime.spawn_with_result("slow", 1, v -> {
                if (v == 11) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("slow release deadline");
                }
                return Math.addExact(v, 100);
            });
            var fast = runtime.spawn_with_result("fast", 2, v -> v * 2);
            var first = accept(runtime, slow, 11);
            require(entered.await(5, TimeUnit.SECONDS), "slow turn never started");
            var queued = accept(runtime, slow, 12);
            var full = runtime.try_send_ticket(slow, 13);
            require(full.admission() == AttoM6Runtime.Admission.FULL && full.ticket() == null,
                "full mailbox minted phantom ticket");
            var other = accept(runtime, fast, 70);
            var otherDone = await(runtime, other);
            require(otherDone.state() == AttoM6Runtime.TicketState.PROCESSED
                && otherDone.hasResult() && otherDone.result() == 140,
                "unrelated actor reply corrupted");
            require(runtime.ticket_snapshot(first).state() == AttoM6Runtime.TicketState.STARTED,
                "another actor completed a blocked first message");
            require(runtime.ticket_snapshot(queued).state() == AttoM6Runtime.TicketState.ADMITTED,
                "another actor completed queued message");
            require(!runtime.ticket_snapshot(queued).hasResult(), "uncommitted result visible");
            var forgedRef = new AttoM6Runtime.ActorRef("slow", slow.generation());
            var forged = runtime.try_send_ticket(forgedRef, 999);
            require(forged.admission() == AttoM6Runtime.Admission.STALE &&
                forged.ticket() == null, "forged record became mailbox authority");
            try (var foreign = new AttoM6Runtime(1, 1)) {
                boolean denied = false;
                try { foreign.ticket_snapshot(first); }
                catch (IllegalArgumentException expected) { denied = true; }
                require(denied, "foreign runtime read an alien ticket");
            }
            release.countDown();
            var one = await(runtime, first);
            var two = await(runtime, queued);
            require(one.state() == AttoM6Runtime.TicketState.PROCESSED &&
                one.hasResult() && one.result() == 111, "first result misrouted");
            require(two.state() == AttoM6Runtime.TicketState.PROCESSED &&
                two.hasResult() && two.result() == 112, "second result misrouted");
            require(runtime.request_stop(slow) && runtime.await_terminal(slow, 10_000),
                "slow actor did not stop");
            require(runtime.request_stop(fast) && runtime.await_terminal(fast, 10_000),
                "fast actor did not stop");
            AttoM6Runtime.check_conservation(runtime.snapshot(slow));
            AttoM6Runtime.check_conservation(runtime.snapshot(fast));
            System.out.println("M6_TICKET_CROSS_ACTOR_AND_FULL_MAILBOX_FENCED=PASS");
        }
    }
    private record Send(long value, AttoM6Runtime.Ticket ticket) {}
    private static void concurrent_exact_results() throws Exception {
        try (var runtime = new AttoM6Runtime(4, 4)) {
            var target = runtime.spawn_with_result("multi", 256, v -> Math.multiplyExact(v, 3));
            var sends = new java.util.concurrent.ConcurrentLinkedQueue<Send>();
            var ready = new CountDownLatch(4);
            var go = new CountDownLatch(1);
            List<Thread> producers = new ArrayList<>();
            var failures = new AtomicInteger();
            for (int p = 0; p < 4; p++) {
                final int partition = p;
                Thread producer = new Thread(() -> {
                    try {
                        ready.countDown();
                        if (!go.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("producer start timeout");
                        for (int i = 0; i < 32; i++) {
                            long value = partition * 1000L + i + 1L;
                            sends.add(new Send(value, accept(runtime, target, value)));
                        }
                    } catch (Throwable t) { failures.incrementAndGet(); }
                }, "proxy-ticket-producer-" + p);
                producers.add(producer);
                producer.start();
            }
            require(ready.await(5, TimeUnit.SECONDS), "producer readiness");
            go.countDown();
            for (Thread thread : producers) thread.join(10_000);
            require(producers.stream().noneMatch(Thread::isAlive) && failures.get() == 0,
                "concurrent enqueue failed");
            require(sends.size() == 128, "concurrent producer lost ticket");
            for (Send sent : sends) {
                var receipt = await(runtime, sent.ticket());
                require(receipt.state() == AttoM6Runtime.TicketState.PROCESSED &&
                    receipt.hasResult() && receipt.result() == sent.value() * 3,
                    "wrong per-message result for " + sent.value());
            }
            require(runtime.request_stop(target) && runtime.await_terminal(target, 10_000),
                "multi actor did not stop");
            var stats = runtime.snapshot(target);
            AttoM6Runtime.check_conservation(stats);
            require(stats.accepted() == 128 && stats.completed() == 128,
                "concurrent per-message receipt not conserved");
            System.out.println("M6_TICKET_128_CONCURRENT_MAIL_EXACT_CORRELATION=PASS");
        }
    }
    private static void failure_abandon_and_restart() throws Exception {
        try (var runtime = new AttoM6Runtime(1, 2)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var target = runtime.spawn_with_result("restarting", 2, value -> {
                if (value == -1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("panic release deadline");
                    throw new ArithmeticException("handler panic");
                }
                return value + 1;
            });
            var failed = accept(runtime, target, -1);
            require(entered.await(5, TimeUnit.SECONDS), "panic turn not started");
            var abandoned = accept(runtime, target, 20);
            require(runtime.ticket_snapshot(failed).state() == AttoM6Runtime.TicketState.STARTED,
                "panic turn not started");
            require(runtime.ticket_snapshot(abandoned).state() == AttoM6Runtime.TicketState.ADMITTED,
                "queued panic neighbor acquired turn");
            release.countDown();
            require(await(runtime, failed).state() == AttoM6Runtime.TicketState.FAILED,
                "actual panicking mail falsely Processed");
            require(await(runtime, abandoned).state() == AttoM6Runtime.TicketState.ABANDONED,
                "queued mail after panic falsely Processed");
            require(runtime.await_terminal(target, 10_000), "PANIC CONTROL not retired");
            var snapshot = runtime.snapshot(target);
            require(snapshot.failed() == 1 && snapshot.abandoned() == 1 &&
                snapshot.controlsDelivered() == 1 &&
                snapshot.lastControl() == AttoM6Runtime.ControlKind.PANIC,
                "panic accounting or CONTROL corrupted");
            AttoM6Runtime.check_conservation(snapshot);
            var restarted = runtime.spawn_with_result("restarting", 1, value -> value + 42);
            require(restarted.generation() == target.generation() + 1,
                "actor incarnation not rotated");
            var old = runtime.try_send_ticket(target, 9);
            require(old.admission() == AttoM6Runtime.Admission.STALE && old.ticket() == null,
                "old incarnation admitted after restart");
            var fresh = accept(runtime, restarted, 8);
            require(await(runtime, fresh).result() == 50, "reincarnated result wrong");
            require(runtime.ticket_snapshot(failed).state() == AttoM6Runtime.TicketState.FAILED,
                "stale failure ticket mutated after reincarnation");
            require(runtime.request_stop(restarted) &&
                runtime.await_terminal(restarted, 10_000), "reincarnated actor stop");
            System.out.println("M6_TICKET_PANIC_ABANDON_CONTROL_REINCARNATION=PASS");
        }
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 0, "host probe does not accept guest source");
        separate_actors_and_mailbox_capacity();
        concurrent_exact_results();
        failure_abandon_and_restart();
        System.out.println("M6_TICKET_SCOPE=HOST_RUNTIME_EXACT_MAIL_NOT_CANONICAL_GUEST_API");
    }
}

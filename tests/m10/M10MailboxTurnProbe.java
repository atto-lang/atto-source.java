// Real M6 destination-host mail tickets, not host-issued futures or
// aggregate counters. Separate from compiled guest and production IPC proof.
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class M10MailboxTurnProbe {
    private static void require(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }
    private static AttoM6Runtime.ReceiptRoute route(
            AttoM6Runtime.ActorRef ref, long messageId) {
        return new AttoM6Runtime.ReceiptRoute(1, 7, 13,
            Integer.toUnsignedLong(ref.name().hashCode()) + 1L,
            ref.generation(), 19, 23, messageId, 1);
    }
    private static AttoM6Runtime.Ticket admit(
            AttoM6Runtime runtime, AttoM6Runtime.ActorRef ref, long value, long id) {
        var actual = runtime.try_send_ticket(ref, value, route(ref, id));
        require(actual.admission() == AttoM6Runtime.Admission.ACCEPTED &&
            actual.ticket() != null, "destination did not mint exact-message ticket");
        require(runtime.ticket_route(actual.ticket()).equals(route(ref, id)),
            "ticket lost full route binding");
        return actual.ticket();
    }
    private static AttoM6Runtime.TicketSnapshot settled(
            AttoM6Runtime runtime, AttoM6Runtime.Ticket ticket)
            throws InterruptedException {
        return runtime.await_ticket(ticket, AttoM6Runtime.TicketState.PROCESSED, 5_000);
    }
    public static void main(String[] args) throws Exception {
        try (var runtime = new AttoM6Runtime(2, 8)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var actor = runtime.spawn_with_result("turn", 2, value -> {
                if (value == 1) {
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("test actor release missing");
                }
                if (value == 2) throw new ArithmeticException("faulting actor turn");
                return Math.addExact(value, 10);
            });
            var a = admit(runtime, actor, 1, 101);
            require(started.await(5, TimeUnit.SECONDS), "first turn did not start");
            require(runtime.ticket_snapshot(a).state() == AttoM6Runtime.TicketState.STARTED,
                "first turn not running");
            var b = admit(runtime, actor, 2, 102);
            var c = admit(runtime, actor, 3, 103);
            require(runtime.try_send_ticket(actor, 4, route(actor, 104)).admission() ==
                AttoM6Runtime.Admission.FULL, "full bounded mailbox admitted overflow");
            require(runtime.ticket_snapshot(b).state() == AttoM6Runtime.TicketState.ADMITTED &&
                runtime.ticket_snapshot(c).state() == AttoM6Runtime.TicketState.ADMITTED,
                "queued turns settled before execution");
            release.countDown();
            var first = settled(runtime, a);
            require(first.state() == AttoM6Runtime.TicketState.PROCESSED &&
                first.hasResult() && first.result() == 11,
                "successful exact turn result not committed");
            require(settled(runtime, b).state() == AttoM6Runtime.TicketState.FAILED,
                "fault turn reported success");
            require(settled(runtime, c).state() == AttoM6Runtime.TicketState.ABANDONED,
                "queued turn was not abandoned");
            require(runtime.await_terminal(actor, 5_000), "panic supervisor failed to retire actor");
            var snap = runtime.snapshot(actor);
            require(snap.accepted() == 3 && snap.completed() == 1 &&
                snap.failed() == 1 && snap.abandoned() == 1 &&
                snap.lastControl() == AttoM6Runtime.ControlKind.PANIC,
                "actor settlement/supervisor counters inconsistent: " + snap);
            AttoM6Runtime.check_conservation(snap);
            var replacement = runtime.spawn_with_result("turn", 2, value -> value);
            require(replacement.generation() == actor.generation() + 1,
                "actor generation reused after panic");
            require(runtime.try_send(actor, 8) == AttoM6Runtime.Admission.STALE,
                "stale ActorRef sent to replacement");
            require(runtime.request_stop(replacement) &&
                runtime.await_terminal(replacement, 5_000), "new actor shutdown failed");
        }
        System.out.println("M10_ATOMIC_MAILBOX_ACK_PROCESSED_FAILED_ABANDONED=PASS");
        System.out.println("M10_PANIC_CONTROL_AND_REINCARNATION_FENCE=PASS");
    }
}

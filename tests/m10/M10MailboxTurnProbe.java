// Verify mailbox-item receipts originate at actual M6 turn settlement.
// HOST-JVM test; no .atto compiler or TLS is involved.
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class M10MailboxTurnProbe {
    private static void require(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }
    public static void main(String[] args) throws Exception {
        try (var runtime = new AttoM6Runtime(2, 8)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var actor = runtime.spawn_reply_actor("turn", 2, value -> {
                if (value == 1) {
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("test actor release missing");
                }
                if (value == 2) throw new ArithmeticException("faulting actor turn");
                return Math.addExact(value, 10);
            });
            var a = new CompletableFuture<AttoM6Runtime.TurnResult>();
            var b = new CompletableFuture<AttoM6Runtime.TurnResult>();
            var c = new CompletableFuture<AttoM6Runtime.TurnResult>();
            require(runtime.try_send_tracked(actor, 1, a) == AttoM6Runtime.Admission.ACCEPTED,
                    "first mailbox admission");
            require(started.await(5, TimeUnit.SECONDS), "first turn did not start");
            require(!a.isDone(), "premature turn completion");
            require(runtime.try_send_tracked(actor, 2, b) == AttoM6Runtime.Admission.ACCEPTED,
                    "fault turn not accepted");
            require(runtime.try_send_tracked(actor, 3, c) == AttoM6Runtime.Admission.ACCEPTED,
                    "queued turn not accepted");
            require(runtime.try_send(actor, 4) == AttoM6Runtime.Admission.FULL,
                    "full bounded mailbox admitted overflow");
            require(!b.isDone() && !c.isDone(), "queued turns completed before execution");
            release.countDown();
            require(a.get(5, TimeUnit.SECONDS).equals(
                    new AttoM6Runtime.TurnResult(AttoM6Runtime.TurnOutcome.PROCESSED, 11)),
                    "successful turn result not committed");
            require(b.get(5, TimeUnit.SECONDS).outcome() ==
                    AttoM6Runtime.TurnOutcome.FAILED, "fault turn reported success");
            require(c.get(5, TimeUnit.SECONDS).outcome() ==
                    AttoM6Runtime.TurnOutcome.ABANDONED, "queued turn was not abandoned");
            require(runtime.await_terminal(actor, 5_000), "panic supervisor failed to retire actor");
            var snap = runtime.snapshot(actor);
            require(snap.accepted() == 3 && snap.completed() == 1 &&
                    snap.failed() == 1 && snap.abandoned() == 1 &&
                    snap.lastControl() == AttoM6Runtime.ControlKind.PANIC,
                    "actor settlement/supervisor counters inconsistent: " + snap);
            AttoM6Runtime.check_conservation(snap);
            var replacement = runtime.spawn_reply_actor("turn", 2, value -> value);
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

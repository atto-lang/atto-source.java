package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
final class LogicalPhysicalReceiptCarrierGuardTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String ACTOR = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 1; return; }
              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    @Test
    void executingSchedulerTurnCannotWaitOnPhysicalMailReceipt()
            throws Exception {
        try (var manager = new HotReloadManager(
                     IsolatePolicy.developer(), ExecutionProfile.serverJit());
             var scheduler = new OresScheduler(1)) {
            var generation = manager.stageActorGeneration("carrier-receipt.ores", ACTOR);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         generation, ActorRuntime.ActorKind.SHARED,
                         "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                byte[] wire = new byte[Long.BYTES];
                wire[Long.BYTES - 1] = 41;
                var envelope = mailbox.admit(wire);
                assertSame(envelope, mailbox.pollDispatch());
                var receipt = router.sendAndDrainForProxy(
                        mailbox, envelope, mailbox.snapshot().routeEpoch());

                var attempted = scheduler.start(resume -> {
                    assertThrows(SecurityException.class,
                            () -> receipt.awaitAndComplete(envelope, WAIT),
                            "the physical completion handle must remain host-only");
                    return OresScheduler.done(1);
                });
                assertEquals(1, attempted.get(5, TimeUnit.SECONDS));
                assertEquals(1, mailbox.snapshot().retainedMessages(),
                        "rejected carrier attempt must not refund credits");

                receipt.awaitAndComplete(envelope, WAIT);
                assertEquals(0, mailbox.snapshot().retainedMessages());
                assertEquals(1L, mailbox.snapshot().completionWatermark());
            }
        }
    }
}

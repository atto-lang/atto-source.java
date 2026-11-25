package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ProtocolGuestCancellationTest {
    @Test
    void rootCancellationKeepsSchedulerAvailableAndAwaitsPhysicalActorExit() throws Exception {
        var plan = ProtocolActorMir.lower("""
                define protocol Connection as
                    initial Waiting;
                    Waiting -> Done on connect;
                    terminal Done;
                end
                define actor Worker implements Connection as
                    connect() -> void { transition Done; }
                end
                pub fnc main() -> int {
                    val worker = spawn Worker();
                    worker.send(connect());
                    val status = worker.get_receipt();
                    worker.stop();
                    return status;
                }
                """);
        var guest = plan.program().modules().getFirst().declarations().stream()
                .filter(Ast.ProtocolGuestDecl.class::isInstance).map(Ast.ProtocolGuestDecl.class::cast)
                .findFirst().orElseThrow();
        var inPhysicalTurn = new CountDownLatch(1);
        var allowExit = new CountDownLatch(1);
        var firstTurn = new AtomicBoolean(true);
        try (var store = new SharedCodeImageStore(); var scheduler = new OresScheduler(1);
             var runtime = new ActorRuntime(IsolatePolicy.developer(), ActorRuntime.DispatcherConfig.defaults(), turn -> {
                 turn.run();
                 if (!firstTurn.compareAndSet(true, false)) return;
                 inPhysicalTurn.countDown();
                 try {
                     if (!allowExit.await(10, TimeUnit.SECONDS))
                         throw new AssertionError("Actor turn was never released");
                 } catch (InterruptedException interrupted) {
                     Thread.currentThread().interrupt();
                     throw new AssertionError(interrupted);
                 }
             })) {
            var image = store.publish("protocol-cancellation.atto", plan.program());
            var task = ProtocolActorMir.startGuest(guest, runtime, image, scheduler);
            try {
                assertTrue(inPhysicalTurn.await(5, TimeUnit.SECONDS));
                assertEquals(1, runtime.actorCount());
                assertTrue(task.cancel(false));
                // The only root carrier must remain available while STOP awaits
                // the actor dispatcher's actual physical turn retirement.
                assertEquals(42, scheduler.start(resume -> OresScheduler.done(42)).get(5, TimeUnit.SECONDS));
                assertFalse(task.isDone(), "cancellation settled before physical actor exit");
                assertEquals(1, runtime.actorCount());
            } finally {
                allowExit.countDown();
            }
            assertThrows(CancellationException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount());
        }
    }
}

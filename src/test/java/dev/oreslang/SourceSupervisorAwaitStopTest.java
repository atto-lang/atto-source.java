package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.SourceSupervisorLowering;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SupervisorChildRegistry;
import dev.oreslang.runtime.SupervisorProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit restricted source hook: awaiting host STOP is a logical CONTROL
 * continuation, not a blocking worker or an implicit JVM guest method.
 */
@Timeout(30)
final class SourceSupervisorAwaitStopTest {
    private static final String SOURCE = """
            define supervisor Root as
              @Implements
              pub async on_panic(f: Failure): void {
                await self.stop(f);
                return;
              }
            end
            """;

    @Test
    void asyncAwaitStopIsAdmittedOnlyThroughRestrictedSourceCompiler() {
        var compiled = OresCompiler.compileStopOnlySupervisor(SOURCE);
        assertEquals("Root", compiled.sourceName());
        assertTrue(compiled.explicitlyBoundFailure());
        assertTrue(compiled.awaitsStopAcknowledgment());
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(SOURCE),
                "general guest supervisor lowering must remain fail closed");
        for (String body : new String[] {
                "await self.restart(f); return;",
                "await self.stop(f.actor); return;",
                "await self.stop(other); return;",
                "await self.stop(f); await self.stop(f); return;",
                "defer self.stop(f); await self.stop(f); return;",
                "await self.stop(f); print(3); return;",
                "try { await self.stop(f); } finally { return; }"
        }) {
            String forged = "define supervisor Root as "
                    + "async on_panic(f: Failure): void { " + body + " } end";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.compileStopOnlySupervisor(forged), forged);
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileStopOnlySupervisor(SOURCE.replace(
                        "pub async on_panic", "pub on_panic")));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileStopOnlySupervisor(SOURCE.replace(
                        "await self.stop(f)", "self.stop(f)")));
    }

    @Test
    void hostAcknowledgmentRetainsTurnUntilFinalizedStopOnControl() throws Exception {
        var compiled = OresCompiler.compileStopOnlySupervisor(SOURCE);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var hostAck = new CompletableFuture<Void>();
            var decisionSeen = new CountDownLatch(1);
            var effects = new AtomicInteger();
            var observed = new AtomicReference<SupervisorProcess.Resolution>();
            var ranInActor = new AtomicReference<Boolean>();
            var process = compiled.installAwaitingStop(group, 2, resolution -> {
                ranInActor.set(ActorRuntime.inActorExecution());
                observed.set(resolution);
                effects.incrementAndGet();
                decisionSeen.countDown();
                return hostAck;
            });
            var actor = group.spawnWorker(ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(), factory -> (message, ctx) -> {
                        throw new IllegalStateException("async-source-stop");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("panic");
            assertTrue(decisionSeen.await(10, TimeUnit.SECONDS));
            var decision = observed.get();
            assertNotNull(decision);
            assertEquals(SupervisorProcess.Directive.STOP, decision.directive());
            assertEquals(actor.id(), decision.notice().actorId());
            assertTrue(actor.done().isDone(), "CONTROL ran before physical finalization");
            assertFalse(actor.isAlive());
            assertEquals(Boolean.FALSE, ranInActor.get());
            var logicalChild = decision.notice().logicalChild().orElseThrow();
            assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                    group.childSnapshot(logicalChild).phase());
            assertTrue(process.callbackActive(), "await dropped logical CONTROL lease");
            assertEquals(0, process.resolved(), "STOP falsely committed before host acknowledgment");

            // Completes on the test thread; group child finalization must
            // resume on CONTROL, not on this foreign completion thread.
            hostAck.complete(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.resolved() == 0 && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertEquals(1, process.resolved());
            assertEquals(1, effects.get());
            assertEquals(0, process.undelivered());
            assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                    group.childSnapshot(logicalChild).phase());
            assertTrue(process.healthy());
            assertFalse(process.callbackActive());
            process.close();
            group.close();
        }
    }

    @Test
    void failedHostAcknowledgmentNeverClaimsStopOrReplaysDecision() throws Exception {
        var compiled = OresCompiler.compileStopOnlySupervisor(SOURCE);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var ack = new CompletableFuture<Void>();
            var invoked = new CountDownLatch(1);
            var observed = new AtomicReference<SupervisorProcess.Resolution>();
            var count = new AtomicInteger();
            var process = compiled.installAwaitingStop(group, 2, resolution -> {
                count.incrementAndGet();
                observed.set(resolution);
                invoked.countDown();
                return ack;
            });
            var actor = group.spawnWorker(ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(), factory -> (message, ctx) -> {
                        throw new IllegalStateException("failed-ack");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("panic");
            assertTrue(invoked.await(10, TimeUnit.SECONDS));
            var child = observed.get().notice().logicalChild().orElseThrow();
            ack.completeExceptionally(new IllegalStateException("STOP host effect failed"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.failure().isEmpty() && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertTrue(process.failure().orElse("").contains("STOP host effect failed"));
            assertEquals(0, process.resolved());
            assertEquals(1, process.undelivered());
            assertEquals(1, count.get());
            assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                    group.childSnapshot(child).phase(), "failed host effect forged STOP");
            process.close();
            group.close();
        }
    }

    @Test
    void closingAwaitingProcessInvalidatesLateAcknowledgment() throws Exception {
        var compiled = OresCompiler.compileStopOnlySupervisor(SOURCE);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var ack = new CompletableFuture<Void>();
            var invoked = new CountDownLatch(1);
            var observed = new AtomicReference<SupervisorProcess.Resolution>();
            var process = compiled.installAwaitingStop(group, 2, resolution -> {
                observed.set(resolution);
                invoked.countDown();
                return ack;
            });
            var actor = group.spawnWorker(ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(), factory -> (message, ctx) -> {
                        throw new IllegalStateException("shutdown-ack");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("panic");
            assertTrue(invoked.await(10, TimeUnit.SECONDS));
            var child = observed.get().notice().logicalChild().orElseThrow();
            process.close();
            assertEquals(1, process.undelivered());
            ack.complete(null); // late completion must never resurrect CONTROL.
            assertEquals(0, process.resolved());
            assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                    group.childSnapshot(child).phase(), "late ack after close forged STOP");
            group.close();
        }
    }

    @Test
    void reentrantSelfCloseCompletesAlreadyAuthorizedAwaitedStopExactlyOnce()
            throws Exception {
        var compiled = OresCompiler.compileStopOnlySupervisor(SOURCE);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var admitted = new CountDownLatch(1);
            var requested = new AtomicReference<SupervisorProcess.Resolution>();
            var processRef = new AtomicReference<SupervisorProcess>();
            var count = new AtomicInteger();
            var process = compiled.installAwaitingStop(group, 2, resolution -> {
                requested.set(resolution);
                count.incrementAndGet();
                // Host STOP can close its own supervisor before returning
                // the already-authoritative effect acknowledgment.
                processRef.get().close();
                admitted.countDown();
                return CompletableFuture.completedFuture(null);
            });
            processRef.set(process);
            var actor = group.spawnWorker(ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(), factory -> (message, ctx) -> {
                        throw new IllegalStateException("reentrant-stop");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("panic");
            assertTrue(admitted.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (process.resolved() == 0 && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertEquals(1, process.resolved(), "self-close erased an admitted decision");
            assertEquals(1, count.get());
            assertEquals(0, process.undelivered());
            assertEquals(0, process.queued());
            assertFalse(process.callbackActive());
            assertFalse(process.healthy(), "closed process still reports healthy");
            assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                    group.childSnapshot(requested.get().notice().logicalChild().orElseThrow())
                            .phase());
            process.close();
            assertEquals(0, process.undelivered(), "second close duplicated loss");
            group.close();
        }
    }
}

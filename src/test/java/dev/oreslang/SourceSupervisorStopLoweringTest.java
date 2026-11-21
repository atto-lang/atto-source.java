package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.SourceSupervisorLowering;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SupervisorProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end restricted guest source -> host-owned CONTROL STOP admission. */
@Timeout(30)
final class SourceSupervisorStopLoweringTest {
    private static final String SOURCE = """
            define supervisor Root as
                @Implements
                on_panic(f: Failure): void {
                    self.stop();
                    return;
                }
            end
            """;

    @Test
    void exactStopHookCompilesButGeneralCompilerStaysClosed() {
        SourceSupervisorLowering.StopOnlySupervisor compiled =
                OresCompiler.compileStopOnlySupervisor(SOURCE);
        assertEquals("Root", compiled.sourceName());
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(SOURCE));
    }


    @Test
    void implicitControlDoesNotRequireAnAnnotationOrAnExplicitLock() {
        String source = """
                define supervisor Root as
                  pub on_panic(f: Failure): void {
                    self.stop();
                    return;
                  }
                end
                """;
        var lowered = OresCompiler.compileStopOnlySupervisor(source);
        assertEquals("Root", lowered.sourceName());
        // STOP-only is opt-in; the general source compiler must still reject
        // lifecycle hooks without complete guest CONTROL lowering.
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }


    @Test
    void typedFailureBinderTargetsOnlyTheCurrentFailure() {
        String source = """
                define supervisor Root as
                  pub on_panic(problem: Failure): void {
                    self.stop(problem);
                    return;
                  }
                end
                """;
        var compiled = OresCompiler.compileStopOnlySupervisor(source);
        assertEquals("Root", compiled.sourceName());
        assertTrue(compiled.explicitlyBoundFailure());
        var childSource = source.replace("self.stop(problem);", "self.stop(problem.child);");
        var childPlan = OresCompiler.compileStopOnlySupervisor(childSource);
        assertTrue(childPlan.explicitlyBoundFailure());
        assertTrue(childPlan.targetsLogicalChild(),
                "checked Failure.child is a restricted logical-child STOP target");
        assertFalse(compiled.targetsLogicalChild());
        assertFalse(OresCompiler.compileStopOnlySupervisor(SOURCE)
                .explicitlyBoundFailure());
        // Normal compilation is still closed until general guest CONTROL
        // lowering exists, regardless of a syntactically valid typed binder.
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));

        for (String body : new String[] {
                "self.stop(wrong); return;",
                "self.stop(self); return;",
                "self.stop(problem.child.actor); return;",
                "self.stop(problem.actor); return;",
                "using self.control { self.stop(); } return;",
                "using supervisor.control { self.stop(); } return;",
                "self.stop(problem, problem); return;",
                "self.stop(42); return;",
                "self.stop(); self.stop(problem); return;"
        }) {
            String forged = "define supervisor Root as "
                    + "on_panic(problem: Failure): void { " + body + " } end";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.compileStopOnlySupervisor(forged),
                    "forged or unsupported failure target: " + body);
        }
    }

    @Test
    void foreignStatementsAndUnsupportedLifecycleEffectsFailClosed() {
        for (String body : new String[] {
                "return;",
                "self.restart(); return;",
                "self.escalate(); return;",
                "self.stop(); self.stop(); return;",
                "print(1); self.stop(); return;",
                "self.stop(); return 1;",
                "self.stop(1); return;",
                "self.stop();",
                "return; self.stop();",
                "await self.stop(); return;",
                "f.stop(); return;"
        }) {
            String source = "define supervisor Root as "
                    + "on_panic(f: Failure): void { " + body + " } end";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.compileStopOnlySupervisor(source), source);
        }
        for (String signature : new String[] {
                "async on_panic(f: Failure)",
                "on_panic(f: String)",
                "on_panic(f: Failure, g: Failure)",
                "on_panic(f: Failure): int"
        }) {
            String head = signature.contains("): ") ? signature
                    : signature + ": void";
            String source = "define supervisor Root as "
                    + head + " { self.stop(); return; } end";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.compileStopOnlySupervisor(source), source);
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileStopOnlySupervisor(SOURCE
                        .replace("end\n", "tear_down(): void { return; }\nend\n")));
    }


    @Test
    void typedFailureTargetBindsToPhysicallyFailedActorNotice() throws Exception {
        String source = """
                define supervisor Root as
                  on_panic(problem: Failure): void {
                    self.stop(problem.child);
                    return;
                  }
                end
                """;
        var compiled = OresCompiler.compileStopOnlySupervisor(source);
        assertTrue(compiled.targetsLogicalChild());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<SupervisorProcess.Resolution> observed =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorRef<String>> failedRef =
                    new AtomicReference<>();
            AtomicReference<Boolean> doneAtDecision = new AtomicReference<>();
            var process = compiled.install(group, 2, resolution -> {
                ActorRuntime.ActorRef<String> finished = failedRef.get();
                doneAtDecision.set(finished != null
                        && finished.done().isDone()
                        && !finished.isAlive());
                observed.set(resolution);
                delivered.countDown();
            });
            ActorRuntime.ActorRef<String> failed = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    factory -> (message, ctx) -> {
                        throw new IllegalStateException("typed-failure-stop");
                    });
            failed.getReady().get(5, TimeUnit.SECONDS);
            var failedId = failed.id();
            failedRef.set(failed);
            failed.send("panic");
            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            var decision = observed.get();
            assertNotNull(decision);
            assertEquals(SupervisorProcess.Directive.STOP, decision.directive());
            assertEquals(failedId, decision.notice().actorId(),
                    "typed Failure STOP retargeted a different child");
            var logicalChild = decision.notice().logicalChild().orElseThrow();
            assertEquals(1, decision.notice().incarnation());
            var incarnation = group.childSnapshot(logicalChild);
            assertEquals(failedId, incarnation.actor());
            assertEquals(1, incarnation.incarnation());
            assertNotEquals(dev.oreslang.runtime.SupervisorChildRegistry.Phase.ACTIVE,
                    incarnation.phase(),
                    "CONTROL received an active, unfinalized child");
            assertEquals(Boolean.TRUE, doneAtDecision.get(),
                    "CONTROL delivered STOP before child done/finalization barrier");
            assertEquals(group.supervisorId(), decision.notice().supervisorId());
            assertTrue(decision.turnGeneration() > 0);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.resolved() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, process.resolved());
            assertEquals(0, process.undelivered());
            assertEquals(dev.oreslang.runtime.SupervisorChildRegistry.Phase.STOPPED,
                    group.childSnapshot(logicalChild).phase(),
                    "host STOP acknowledgment did not commit child disposition");
            assertThrows(IllegalStateException.class,
                    () -> group.acknowledgeFinalizedStop(decision),
                    "replayed STOP must not consume a second lifecycle generation");
            var wrongActor = new dev.oreslang.runtime.SupervisorFailureControl.Notice(
                    decision.notice().supervisorId(), decision.notice().groupId(),
                    ActorRuntime.ActorId.create(), decision.notice().spawnedBy(),
                    decision.notice().kind(), decision.notice().failureType(),
                    decision.notice().diagnostic(), decision.notice().logicalChild(),
                    decision.notice().incarnation());
            var forged = new SupervisorProcess.Resolution(wrongActor,
                    SupervisorProcess.Directive.STOP, decision.turnGeneration());
            assertThrows(IllegalStateException.class,
                    () -> group.acknowledgeFinalizedStop(forged),
                    "foreign physical incarnation may not consume logical child");
            group.close();
            process.close();
        }
    }

    @Test
    void transferringAWorkerRetiresTheOldLogicalIdentityWithoutStoppingIt()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var original = runtime.createActorGroup();
            var actor = original.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    factory -> (message, ctx) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            assertEquals(1, original.trackedLogicalChildren());
            assertTrue(original.releaseWorker(actor));
            assertEquals(0, original.trackedLogicalChildren(),
                    "ownership transfer retained an old supervisor child");
            assertFalse(original.releaseWorker(actor),
                    "second release must not repeat the transfer");
            var newOwner = runtime.createActorGroup();
            newOwner.manageWorker(actor);
            assertEquals(1, newOwner.trackedLogicalChildren());
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (newOwner.trackedLogicalChildren() != 0
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(0, newOwner.trackedLogicalChildren(),
                    "new supervisor did not retire cleanly finalized child");
            original.close();
            newOwner.close();
        }
    }

    @Test
    void normallyStoppedManagedActorRetiresItsLogicalChild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var actor = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    factory -> (message, ctx) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            assertEquals(1, group.trackedLogicalChildren(),
                    "spawn admission failed to register a logical child");
            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (group.trackedLogicalChildren() != 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(0, group.trackedLogicalChildren(),
                    "normal physical finalization did not retire child registry record");
            group.close();
        }
    }

    @Test
    void compiledStopDoesNotAcknowledgeThrowingHostCommit() throws Exception {
        var compiled = OresCompiler.compileStopOnlySupervisor(SOURCE);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<SupervisorProcess.Resolution> ambiguous =
                    new AtomicReference<>();
            var process = compiled.install(group, 2, decision -> {
                ambiguous.set(decision);
                assertEquals(SupervisorProcess.Directive.STOP, decision.directive());
                assertTrue(decision.turnGeneration() > 0);
                calls.incrementAndGet();
                attempted.countDown();
                throw new IllegalStateException("ambiguous STOP effect");
            });
            var actor = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    factory -> (message, ctx) -> {
                        throw new IllegalStateException("guest-stop-failure");
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("panic");
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(process.failure().orElse("").contains("ambiguous STOP effect"));
            assertEquals(1, calls.get(), "ambiguous STOP was automatically replayed");
            assertEquals(0, process.resolved(), "failed host effect marked committed");
            assertEquals(1, process.undelivered());
            assertEquals(0, process.queued());
            assertFalse(process.healthy());
            var failedChild = ambiguous.get().notice().logicalChild().orElseThrow();
            assertEquals(dev.oreslang.runtime.SupervisorChildRegistry.Phase.STOPPING,
                    group.childSnapshot(failedChild).phase(),
                    "ambiguous host STOP must quarantine the child, not allow RESTART");
            assertEquals(dev.oreslang.runtime.SupervisorChildRegistry.StopReceiptStatus.UNCERTAIN,
                    group.stopReceipt(failedChild).status());
            group.close();
            process.close();
        }
    }

    @Test
    void realManagedGrandchildFailureRunsCompiledStopOnSupervisorControl() throws Exception {
        SourceSupervisorLowering.StopOnlySupervisor compiled =
                OresCompiler.compileStopOnlySupervisor(SOURCE);
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicInteger decisions = new AtomicInteger();
            AtomicReference<SupervisorProcess.Resolution> observed = new AtomicReference<>();
            AtomicReference<Boolean> onActorCarrier = new AtomicReference<>();
            SupervisorProcess process = compiled.install(group, 8, decision -> {
                onActorCarrier.set(ActorRuntime.inActorExecution());
                observed.set(decision);
                decisions.incrementAndGet();
                delivered.countDown();
            });
            var root = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    ctx -> (message, actor) -> {
                        var child = actor.runtime().spawnPrivate(
                                bctx -> (body, receiver) -> {
                                    var grandchild = receiver.runtime().spawnPrivate(
                                            cctx -> (data, grand) -> {
                                                throw new IllegalStateException("source-stop-test");
                                            });
                                    grandchild.send("panic");
                                });
                        child.send("start");
                    });
            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("begin");
            assertTrue(delivered.await(10, TimeUnit.SECONDS),
                    "compiled on_panic STOP did not reach supervisor CONTROL");
            var decision = observed.get();
            assertNotNull(decision);
            assertEquals(SupervisorProcess.Directive.STOP, decision.directive());
            assertEquals(group.id(), decision.notice().groupId());
            assertEquals(group.supervisorId(), decision.notice().supervisorId());
            assertEquals("source-stop-test", decision.notice().diagnostic());
            assertTrue(decision.notice().logicalChild().isPresent(),
                    "nested inherited child had no stable logical identity");
            var child = group.childSnapshot(decision.notice().logicalChild().orElseThrow());
            assertEquals(decision.notice().actorId(), child.actor());
            assertEquals(decision.notice().incarnation(), child.incarnation());
            assertNotEquals(dev.oreslang.runtime.SupervisorChildRegistry.Phase.ACTIVE,
                    child.phase());
            assertEquals(Boolean.FALSE, onActorCarrier.get(),
                    "compiled guest STOP ran on a child actor carrier");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.resolved() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, decisions.get(), "guest source decision applied twice");
            assertEquals(1, process.resolved());
            assertEquals(0, process.undelivered());
            assertTrue(process.healthy());
            assertEquals(dev.oreslang.runtime.SupervisorChildRegistry.Phase.STOPPED,
                    group.childSnapshot(decision.notice().logicalChild().orElseThrow()).phase());
            group.close();
            process.close();
        }
    }
}

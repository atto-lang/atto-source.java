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
            group.close();
            process.close();
        }
    }
}

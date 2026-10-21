package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.ScopedTaskReadAuthority;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ScopedTaskReadRuntimeTest {
    @Test
    void readLeaseSurvivesFutureCancellationUntilPhysicalExit() throws Exception {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr boundary = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);
        var allocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 101, 9, true);
        var authority = ScopedTaskReadAuthority.prove(
                program,
                boundary,
                epoch,
                allocation,
                List.of(),
                Types.Primitive.INT);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> future = ScopedTaskReadRuntime.start(
                    scheduler,
                    resume -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return OresScheduler.done(1);
                    },
                    authority,
                    program,
                    boundary,
                    epoch,
                    allocation.allocationId(),
                    allocation.generation());

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1,
                    ScopedTaskReadRuntime.activeReadersForTesting(allocation));
            assertThrows(IllegalStateException.class,
                    () -> ScopedTaskReadRuntime.requireWritable(allocation));

            future.cancel(false);
            assertEquals(1,
                    ScopedTaskReadRuntime.activeReadersForTesting(allocation),
                    "Future cancellation is not physical Task retirement");
            assertThrows(IllegalStateException.class,
                    () -> ScopedTaskReadRuntime.requireWritable(allocation));

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ScopedTaskReadRuntime.activeReadersForTesting(allocation) != 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0,
                    ScopedTaskReadRuntime.activeReadersForTesting(allocation));
            assertDoesNotThrow(
                    () -> ScopedTaskReadRuntime.requireWritable(allocation));
        }
    }


    @Test
    void writeLeaseBlocksReaderAdmissionUntilPhysicalWriteEnds() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr boundary = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);
        var allocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 303, 2, true);
        var authority = ScopedTaskReadAuthority.prove(
                program,
                boundary,
                epoch,
                allocation,
                List.of(),
                Types.Primitive.INT);

        try (OresScheduler scheduler = new OresScheduler(1);
             ScopedTaskReadRuntime.WriteLease ignored =
                     ScopedTaskReadRuntime.acquireWrite(allocation)) {
            assertTrue(ScopedTaskReadRuntime.writeHeldForTesting(allocation));
            assertThrows(IllegalStateException.class,
                    () -> ScopedTaskReadRuntime.start(
                            scheduler,
                            resume -> OresScheduler.done(1),
                            authority,
                            program,
                            boundary,
                            epoch,
                            allocation.allocationId(),
                            allocation.generation()));
        }

        assertFalse(ScopedTaskReadRuntime.writeHeldForTesting(allocation));
        assertDoesNotThrow(
                () -> ScopedTaskReadRuntime.requireWritable(allocation));
    }

    @Test
    void readerAndWriterAdmissionAreLinearizedUnderRace() throws Exception {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr boundary = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);
        var allocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 404, 6, true);
        var authority = ScopedTaskReadAuthority.prove(
                program,
                boundary,
                epoch,
                allocation,
                List.of(),
                Types.Primitive.INT);

        for (int round = 0; round < 128; round++) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch taskRelease = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<
                    ScopedTaskReadRuntime.WriteLease> writeLease =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> readerFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> writerFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();

            try (OresScheduler scheduler = new OresScheduler(1)) {
                Thread reader = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        ScopedTaskReadRuntime.start(
                                scheduler,
                                resume -> {
                                    taskRelease.await(5, TimeUnit.SECONDS);
                                    return OresScheduler.done(1);
                                },
                                authority,
                                program,
                                boundary,
                                epoch,
                                allocation.allocationId(),
                                allocation.generation());
                    } catch (Throwable failure) {
                        readerFailure.set(failure);
                    }
                });
                Thread writer = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        writeLease.set(
                                ScopedTaskReadRuntime.acquireWrite(allocation));
                    } catch (Throwable failure) {
                        writerFailure.set(failure);
                    }
                });

                start.countDown();
                reader.join(5000);
                writer.join(5000);
                assertFalse(reader.isAlive());
                assertFalse(writer.isAlive());

                boolean readerWon = readerFailure.get() == null;
                boolean writerWon = writerFailure.get() == null;
                assertNotEquals(readerWon, writerWon,
                        "exactly one conflicting READ/WRITE admission must win");

                if (writerWon) {
                    assertTrue(ScopedTaskReadRuntime.writeHeldForTesting(allocation));
                    assertEquals(0,
                            ScopedTaskReadRuntime.activeReadersForTesting(allocation));
                    writeLease.get().close();
                } else {
                    assertEquals(1,
                            ScopedTaskReadRuntime.activeReadersForTesting(allocation));
                    assertFalse(ScopedTaskReadRuntime.writeHeldForTesting(allocation));
                    taskRelease.countDown();
                    long deadline =
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (ScopedTaskReadRuntime.activeReadersForTesting(allocation) != 0
                            && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertEquals(0,
                            ScopedTaskReadRuntime.activeReadersForTesting(allocation));
                }
            } finally {
                taskRelease.countDown();
                ScopedTaskReadRuntime.WriteLease lease = writeLease.get();
                if (lease != null
                        && ScopedTaskReadRuntime.writeHeldForTesting(allocation)) {
                    lease.close();
                }
            }
        }
    }

    @Test
    void staleGenerationDoesNotBlockReplacementAllocation() {
        var oldAllocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 7, 1, true);
        var replacement = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 7, 2, true);
        assertDoesNotThrow(
                () -> ScopedTaskReadRuntime.requireWritable(oldAllocation));
        assertDoesNotThrow(
                () -> ScopedTaskReadRuntime.requireWritable(replacement));
    }

    private static Ast.TaskExpr firstTask(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl function)) continue;
                for (Ast.Stmt statement : function.body()) {
                    if (statement instanceof Ast.BindingStmt binding
                            && binding.initializer() instanceof Ast.TaskExpr task) {
                        return task;
                    }
                }
            }
        }
        throw new AssertionError("expected TaskExpr");
    }
}

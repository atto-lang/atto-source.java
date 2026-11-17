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
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));
            assertThrows(IllegalStateException.class,
                    () -> ScopedTaskReadRuntime.requireWritable(program, allocation));

            future.cancel(false);
            assertEquals(1,
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation),
                    "Future cancellation is not physical Task retirement");
            assertThrows(IllegalStateException.class,
                    () -> ScopedTaskReadRuntime.requireWritable(program, allocation));

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ScopedTaskReadRuntime.activeReadersForTesting(program, allocation) != 0
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0,
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));
            assertDoesNotThrow(
                    () -> ScopedTaskReadRuntime.requireWritable(program, allocation));
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
                     ScopedTaskReadRuntime.acquireWrite(program, allocation)) {
            assertTrue(ScopedTaskReadRuntime.writeHeldForTesting(program, allocation));
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

        assertFalse(ScopedTaskReadRuntime.writeHeldForTesting(program, allocation));
        assertDoesNotThrow(
                () -> ScopedTaskReadRuntime.requireWritable(program, allocation));
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
                                ScopedTaskReadRuntime.acquireWrite(program, allocation));
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
                    assertTrue(ScopedTaskReadRuntime.writeHeldForTesting(program, allocation));
                    assertEquals(0,
                            ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));
                    writeLease.get().close();
                } else {
                    assertEquals(1,
                            ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));
                    assertFalse(ScopedTaskReadRuntime.writeHeldForTesting(program, allocation));
                    taskRelease.countDown();
                    long deadline =
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (ScopedTaskReadRuntime.activeReadersForTesting(program, allocation) != 0
                            && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertEquals(0,
                            ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));
                }
            } finally {
                taskRelease.countDown();
                ScopedTaskReadRuntime.WriteLease lease = writeLease.get();
                if (lease != null
                        && ScopedTaskReadRuntime.writeHeldForTesting(program, allocation)) {
                    lease.close();
                }
            }
        }
    }

    @Test
    void structuredScopeRetiresReadOnlyAtExactChildQuiescence() throws Exception {
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
                "PROCESS", "root", 505, 8, true);
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
            TaskScope scope = scheduler.newTaskScope();
            var read = ScopedTaskReadRuntime.acquireRead(
                    authority, program, boundary, epoch,
                    allocation.allocationId(), allocation.generation());
            TaskHandle<Integer> child = ScopedTaskReadRuntime.startInScope(
                    scope,
                    resume -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return OresScheduler.done(1);
                    },
                    read);

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1,
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));
            child.result().cancel(false);
            scope.seal();

            assertEquals(1,
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation),
                    "result settlement/cancellation must not retire READ authority");
            assertFalse(scope.getDone().isDone(),
                    "scope cannot report quiescence while the physical child is running");

            release.countDown();
            assertNull(scope.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0,
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));
            assertDoesNotThrow(
                    () -> ScopedTaskReadRuntime.requireWritable(program, allocation));
        }
    }

    @Test
    void structuredScopePreAdmissionRejectionRollsBackReadLease() {
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
                "PROCESS", "root", 606, 4, true);
        var authority = ScopedTaskReadAuthority.prove(
                program,
                boundary,
                epoch,
                allocation,
                List.of(),
                Types.Primitive.INT);

        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskScope scope = scheduler.newTaskScope();
            scope.seal();
            var read = ScopedTaskReadRuntime.acquireRead(
                    authority, program, boundary, epoch,
                    allocation.allocationId(), allocation.generation());
            assertEquals(1,
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation));

            assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    () -> ScopedTaskReadRuntime.startInScope(
                            scope,
                            resume -> OresScheduler.done(1),
                            read));
            assertEquals(0,
                    ScopedTaskReadRuntime.activeReadersForTesting(program, allocation),
                    "proven pre-admission rejection must retire its READ reservation");
        }
    }

    @Test
    void duplicateReadRetirementPoisonsStructuredChildOutcome() throws Exception {
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
                "PROCESS", "root", 707, 3, true);
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
            TaskScope scope = scheduler.newTaskScope();
            var read = ScopedTaskReadRuntime.acquireRead(
                    authority, program, boundary, epoch,
                    allocation.allocationId(), allocation.generation());
            ScopedTaskReadRuntime.startInScope(
                    scope,
                    resume -> {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return OresScheduler.done(1);
                    },
                    read);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Fault injection: an early/forged retirement must not allow the
            // later exact physical callback to pass silently.
            read.close();
            scope.seal();
            release.countDown();
            assertNull(scope.getDone().get(5, TimeUnit.SECONDS));

            var retained = assertInstanceOf(
                    TaskHandle.Failed.class,
                    scope.firstNonSuccessOutcome());
            assertTrue(retained.failure().getMessage().contains("more than once"),
                    retained.failure()::getMessage);
        }
    }


    @Test
    void processOwnerLabelCannotAliasSameAllocationGeneration() {
        Ast.Program program = checkedProgram();
        var first = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "first-label", 606, 4, true);
        var second = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "second-label", 606, 4, true);

        try (ScopedTaskReadRuntime.WriteLease ignored =
                     ScopedTaskReadRuntime.acquireWrite(program, first)) {
            assertThrows(IllegalStateException.class,
                    () -> ScopedTaskReadRuntime.acquireWrite(program, second));
            assertThrows(IllegalStateException.class,
                    () -> ScopedTaskReadRuntime.requireWritable(program, second));
        }
        assertDoesNotThrow(
                () -> ScopedTaskReadRuntime.requireWritable(program, second));
    }

    @Test
    void separateCheckedProgramsDoNotShareAllocationAuthoritySlots() {
        Ast.Program firstProgram = checkedProgram();
        Ast.Program secondProgram = checkedProgram();
        assertNotSame(firstProgram, secondProgram);

        var allocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 707, 1, true);
        try (ScopedTaskReadRuntime.WriteLease ignored =
                     ScopedTaskReadRuntime.acquireWrite(firstProgram, allocation)) {
            assertDoesNotThrow(
                    () -> ScopedTaskReadRuntime.requireWritable(
                            secondProgram, allocation));
            try (ScopedTaskReadRuntime.WriteLease second =
                         ScopedTaskReadRuntime.acquireWrite(
                                 secondProgram, allocation)) {
                assertNotNull(second);
            }
        }
    }

    @Test
    void staleGenerationDoesNotBlockReplacementAllocation() {
        Ast.Program program = checkedProgram();
        var oldAllocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 7, 1, true);
        var replacement = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 7, 2, true);
        assertDoesNotThrow(
                () -> ScopedTaskReadRuntime.requireWritable(
                        program, oldAllocation));
        assertDoesNotThrow(
                () -> ScopedTaskReadRuntime.requireWritable(
                        program, replacement));
    }

    private static Ast.Program checkedProgram() {
        return OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
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

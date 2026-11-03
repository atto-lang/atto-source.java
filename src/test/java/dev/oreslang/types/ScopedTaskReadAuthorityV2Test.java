package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ScopedTaskReadAuthorityV2Test {
    @Test
    void frozenProofIsInertBeforeWholeProgramTaskPublication() {
        Ast.Program program = dev.oreslang.parser.Parser.parse("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        long stagedEpoch = 991;
        var allocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 91, 2, true);

        ScopedTaskReadAuthority frozen =
                ScopedTaskReadAuthority.freezeForOwnershipCheck(
                        program,
                        task,
                        stagedEpoch,
                        allocation,
                        List.of(),
                        Types.Primitive.INT);

        assertNotNull(frozen,
                "ownership analysis must be able to freeze proof before Task publication");
        assertThrows(
                IllegalStateException.class,
                () -> frozen.require(
                        program,
                        task,
                        stagedEpoch,
                        allocation.allocationId(),
                        allocation.generation()),
                "a frozen proof alone must never grant Task execution authority");
    }

    @Test
    void exactCheckedCpuTaskCanCarryCompilerOnlyReadProof() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);

        ScopedTaskReadAuthority authority = ScopedTaskReadAuthority.prove(
                program,
                task,
                epoch,
                new ScopedTaskReadAuthority.AllocationIdentity(
                        "PROCESS", "root", 11, 3, true),
                List.of(
                        new ScopedTaskReadAuthority.Field("payload", true),
                        new ScopedTaskReadAuthority.Range(
                                0, 16,
                                ScopedTaskReadAuthority.RangeUnit.BYTES,
                                true)),
                Types.Primitive.INT);

        assertEquals(2, authority.projections().size());
        assertDoesNotThrow(() -> authority.require(
                program, task, epoch, 11, 3));
    }

    @Test
    void nonSyncReferentAndMutableStyleStorageFailClosed() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);
        var root = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 7, 1, true);

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> ScopedTaskReadAuthority.prove(
                        program, task, epoch, root, List.of(),
                        new Types.Named("RefCell", List.of(Types.Primitive.INT))));
        assertTrue(failure.getMessage().contains("Sync"), failure::getMessage);

        assertThrows(IllegalArgumentException.class,
                () -> new ScopedTaskReadAuthority.Field("x", false));
        assertThrows(IllegalArgumentException.class,
                () -> new ScopedTaskReadAuthority.Range(
                        0, 4,
                        ScopedTaskReadAuthority.RangeUnit.ELEMENTS,
                        false));
    }

    @Test
    void staleProgramTaskEpochGenerationAndDomainAreRejected() {
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);
        var root = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 13, 5, true);
        var authority = ScopedTaskReadAuthority.prove(
                program, task, epoch, root, List.of(),
                Types.Primitive.INT);

        assertThrows(IllegalArgumentException.class,
                () -> authority.require(program, task, epoch, 13, 6));

        OwnershipChecker.check(program);
        // Rechecking revokes the Program's prior ownership admission epoch.
        assertThrows(IllegalStateException.class,
                () -> authority.require(program, task, epoch, 13, 5));

        assertThrows(IllegalArgumentException.class,
                () -> new ScopedTaskReadAuthority.AllocationIdentity(
                        "ACTOR_PRIVATE", "actor-1", 1, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ScopedTaskReadAuthority.AllocationIdentity(
                        "PROCESS", "root", 1, 1, false));
    }

    @Test
    void readonlyRecordViewCannotMintScopedReadAuthority() {
        Ast.Program program = dev.oreslang.parser.Parser.parse("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        var root = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 27, 1, true);
        for (Types.Type view : List.of(
                new Types.Record(java.util.Map.of("value", Types.Primitive.INT), true),
                new Types.Record(java.util.Map.of(), true))) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ScopedTaskReadAuthority.freezeForOwnershipCheck(
                            program, task, 991, root, List.of(), view));
            assertTrue(failure.getMessage().contains("Sync"), failure::getMessage);
        }
    }

    @Test
    void publishedCheckedTaskStillRejectsReadonlyViewProofLaundering() {
        // Prove against an admitted Task epoch, not only the private staging
        // helper on a parser-only Program. Invalid referent types must fail
        // even after successful whole-program capture publication.
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);
        var root = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 37, 1, true);
        var view = new Types.Record(
                java.util.Map.of("value", Types.Primitive.INT), true);

        for (Types.Type referent : List.of(
                view,
                new Types.Record(java.util.Map.of(), true),
                new Types.Named("Option", List.of(view)),
                new Types.Named("Result", List.of(Types.Primitive.INT, view)),
                new Types.Named("Arc", List.of(view)),
                new Types.Named("Weak", List.of(view)))) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ScopedTaskReadAuthority.prove(
                            program, task, epoch, root, List.of(), referent));
            assertTrue(failure.getMessage().contains("Sync"),
                    failure::getMessage);
        }
        // Ensure the negative checks are not satisfied by a stale epoch or
        // incorrectly constructed Task: scalar READ is still admissible.
        assertDoesNotThrow(() -> ScopedTaskReadAuthority.prove(
                program, task, epoch, root, List.of(), Types.Primitive.INT));
    }

    @Test
    void runtimeUnverifiedWrappersCannotMintScopedTaskReadProof() {
        // A general type capability is not automatically proof that a
        // borrowed reference has a verified cross-thread runtime lowering.
        // Keep the independent ThreadSafetyTraits admission boundary.
        Ast.Program program = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(program);
        var allocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 41, 1, true);

        var shared = new Types.Named("SharedMutex", List.of(Types.Primitive.INT));
        for (Types.Type type : List.of(
                shared,
                new Types.Named("Option", List.of(shared)),
                new Types.Named("Result", List.of(Types.Primitive.INT, shared)),
                new Types.Named("ActorRef", List.of(Types.Primitive.INT)),
                new Types.Named("Arc", List.of(Types.Primitive.INT)),
                new Types.Named("Weak", List.of(Types.Primitive.INT)),
                new Types.Named("Box", List.of(Types.Primitive.INT)))) {
            assertTrue(TypeAutoTraits.inferClosed(type).sync(),
                    () -> "regression case no longer exercises general Sync: " + type);
            assertFalse(ThreadSafetyTraits.has(type,
                    ThreadSafetyTraits.Trait.SYNC, (named, trait) -> false));
            var error = assertThrows(IllegalArgumentException.class,
                    () -> ScopedTaskReadAuthority.prove(
                            program, task, epoch, allocation, List.of(), type));
            assertTrue(error.getMessage().contains("verified Sync"), error::getMessage);
        }
        assertDoesNotThrow(() -> ScopedTaskReadAuthority.prove(
                program, task, epoch, allocation, List.of(),
                new Types.Tuple(List.of(Types.Primitive.INT))));
    }

    @Test
    void adversariallyDeepReferentsFailBeforeUnboundedAutoTraitRecursion() {
        // ThreadSafetyTraits has a 256-level depth budget, while generic
        // TypeAutoTraits composition is recursive. Check the trusted boundary
        // first: a 4096-level caller-provided type must fail with a normal
        // diagnostic, never StackOverflowError or forged READ authority.
        Ast.Program program = dev.oreslang.parser.Parser.parse("""
                pub async fnc main(): void {
                  let Future<int> child = task { return 1; };
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(program);
        var allocation = new ScopedTaskReadAuthority.AllocationIdentity(
                "PROCESS", "root", 43, 1, true);
        Types.Type deep = Types.Primitive.INT;
        for (int i = 0; i < 4096; i++) {
            deep = new Types.Named("Option", List.of(deep));
        }
        final Types.Type nested = deep;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ScopedTaskReadAuthority.freezeForOwnershipCheck(
                        program, task, 991, allocation, List.of(), nested));
        assertTrue(failure.getMessage().contains("verified Sync"),
                failure::getMessage);

        assertDoesNotThrow(() -> ScopedTaskReadAuthority.freezeForOwnershipCheck(
                program, task, 991, allocation, List.of(),
                new Types.Tuple(List.of(Types.Primitive.INT))));
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


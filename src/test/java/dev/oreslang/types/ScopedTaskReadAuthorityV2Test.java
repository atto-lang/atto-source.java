package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ScopedTaskReadAuthorityV2Test {
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
        assertThrows(IllegalArgumentException.class,
                () -> authority.require(program, task, epoch, 13, 5));

        assertThrows(IllegalArgumentException.class,
                () -> new ScopedTaskReadAuthority.AllocationIdentity(
                        "ACTOR_PRIVATE", "actor-1", 1, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ScopedTaskReadAuthority.AllocationIdentity(
                        "PROCESS", "root", 1, 1, false));
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

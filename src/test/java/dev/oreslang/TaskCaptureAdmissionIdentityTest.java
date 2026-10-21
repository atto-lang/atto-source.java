package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

final class TaskCaptureAdmissionIdentityTest {
    @Test
    void completeOwnershipPassPublishesExactTaskCaptureProof() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task {
                    return value + 1;
                  };
                  stdio.stdout.write(await child);
                  return;
                }
                """);

        Ast.TaskExpr task = firstTask(checked);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertEquals(
                java.util.Map.of(
                        "value", Ast.TaskCaptureTransfer.COPY),
                Ast.CheckedTaskCaptures.require(
                        checked, task, epoch).captureTransfers());

        Ast.TaskExpr clone =
                new Ast.TaskExpr(task.domain(), task.body(), task.legacyNb());
        assertThrows(
                IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(
                        checked, clone, epoch));

        // Generic optimizer metadata transfer deliberately cannot mint Task
        // execution authority. A transformed Task must pass ownership checking
        // again as part of its resulting program.
        Ast.copyCheckedMetadata(task, clone);
        assertThrows(
                IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(
                        checked, clone, epoch));
    }

    @Test
    void writableCopyCaptureCarriesMoveMode() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  let int counter = 1;
                  let Future<int> child = task {
                    counter = counter + 1;
                    return counter;
                  };
                  stdio.stdout.write(await child);
                  return;
                }
                """);

        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertEquals(
                java.util.Map.of(
                        "counter", Ast.TaskCaptureTransfer.MOVE),
                Ast.CheckedTaskCaptures.require(
                        checked,
                        firstTask(checked),
                        epoch).captureTransfers());
    }

    @Test
    void affineCaptureAdmissionCarriesMoveMode() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub let int value = 1;
                end
                pub async fnc main(): void {
                  let Box owner = new Box();
                  let Future<int> child = task {
                    return owner.value;
                  };
                  stdio.stdout.write(await child);
                  return;
                }
                """);

        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertEquals(
                java.util.Map.of(
                        "owner", Ast.TaskCaptureTransfer.MOVE),
                Ast.CheckedTaskCaptures.require(
                        checked,
                        firstTask(checked),
                        epoch).captureTransfers());
    }

    @Test
    void laterOwnershipFailurePreventsEarlierTaskAdmissionPublication() {
        TypeChecker.TypeCheckResult typed =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end
                        pub async fnc main(): void {
                          const int value = 7;
                          let Future<int> child = task {
                            return value;
                          };
                          let Box owner = new Box();
                          let Box moved = owner;
                          stdio.stdout.write(owner.value);
                          return;
                        }
                        """));

        Ast.TaskExpr task = firstTask(typed.program());
        assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(
                        typed.program(), typed.shortInferredAliases()));
        assertThrows(
                IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.requireProgramEpoch(
                        typed.program()));
    }

    @Test
    void successfulRecheckSupersedesEarlierProgramEpoch() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task {
                    return value + 1;
                  };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(checked);
        long firstEpoch =
                Ast.CheckedTaskCaptures.requireProgramEpoch(checked);

        OwnershipChecker.check(checked);
        long secondEpoch =
                Ast.CheckedTaskCaptures.requireProgramEpoch(checked);

        assertNotEquals(firstEpoch, secondEpoch);
        assertThrows(
                IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(
                        checked, task, firstEpoch));
        assertEquals(
                java.util.Map.of(
                        "value", Ast.TaskCaptureTransfer.COPY),
                Ast.CheckedTaskCaptures.require(
                        checked,
                        task,
                        secondEpoch).captureTransfers());
    }

    private static Ast.TaskExpr firstTask(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl function)) {
                    continue;
                }
                for (Ast.Stmt statement : function.body()) {
                    if (statement instanceof Ast.BindingStmt binding
                            && binding.initializer() instanceof Ast.TaskExpr task) {
                        return task;
                    }
                }
            }
        }
        throw new AssertionError("expected TaskExpr in test program");
    }
}

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
                pub suspends fnc main(): void {
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
                pub suspends fnc main(): void {
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
    void explicitTakeOfCopyCaptureCarriesMoveMode() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub suspends fnc main(): void {
                  const int token = 41;
                  let Future<int> child = task {
                    return rt take token;
                  };
                  stdio.stdout.write(await child);
                  return;
                }
                """);

        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertEquals(
                java.util.Map.of(
                        "token", Ast.TaskCaptureTransfer.MOVE),
                Ast.CheckedTaskCaptures.require(
                        checked,
                        firstTask(checked),
                        epoch).captureTransfers());
    }

    @Test
    void explicitTakeOfCopyCaptureMovesParentAtTaskSubmission() {
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub suspends fnc main(): void {
                          const int token = 41;
                          let Future<int> child = task {
                            return rt take token;
                          };
                          stdio.stdout.write(token);
                          stdio.stdout.write(await child);
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("moved"), failure::getMessage);
    }

    @Test
    void affineCaptureAdmissionCarriesMoveMode() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub let int value = 1;
                end
                pub suspends fnc main(): void {
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
                        pub suspends fnc main(): void {
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
                pub suspends fnc main(): void {
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

    @Test
    void unsuccessfulRecheckCannotRetainPreviouslyValidTaskAuthority() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub suspends fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(checked);
        long accepted = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        long failedAttempt = Ast.CheckedTaskCaptures.newCheckEpoch();
        Ast.CheckedTaskCaptures.beginCheck(checked, failedAttempt);

        // Simulate a checker that rejects the program after beginning a new
        // ownership pass. No publication occurs, and old roots fail closed.
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.requireProgramEpoch(checked));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(checked, task, accepted));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(
                        checked, accepted, java.util.Map.of()));
    }

    @Test
    void failedRealImportedClassRecheckRevokesPreviouslyExecutableTask() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub let int value = 1;
                end
                pub suspends fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(checked);
        long originalEpoch =
                Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        Ast.ClassDecl existingClass = null;
        for (Ast.ModuleDecl module : checked.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl cls
                        && cls.name().equals("Box")) {
                    existingClass = cls;
                }
            }
        }
        assertNotNull(existingClass,
                "fixture must contain a local class used to force linked-class conflict");
        Ast.ClassDecl duplicateClass = existingClass;

        // This is a real OwnershipChecker entrypoint failure, not an explicit
        // test-only call to beginCheck. The import is invalid because Box
        // already exists locally. The checker must revoke prior authorization
        // before it reports the conflicting linked class.
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.checkWithImportedClasses(
                        checked, java.util.Map.of("Box", duplicateClass)));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.requireProgramEpoch(checked));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(
                        checked, task, originalEpoch));
    }

    @Test
    void publicStagedCapturePublisherCannotMintTaskTransferAuthority() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub suspends fnc main(): void {
                  const int value = 41;
                  const int unrelated = 9;
                  let Future<int> child = task { return value + 1; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(checked);
        long revoked = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        long attempt = Ast.CheckedTaskCaptures.newCheckEpoch();
        Ast.CheckedTaskCaptures.beginCheck(checked, attempt);

        java.util.Map<Ast.TaskExpr,
                java.util.Map<String, Ast.TaskCaptureTransfer>> overbroad =
                java.util.Map.of(task, java.util.Map.of(
                        "value", Ast.TaskCaptureTransfer.COPY,
                        "unrelated", Ast.TaskCaptureTransfer.MOVE));
        var denied = assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(
                        checked, attempt, overbroad));
        assertTrue(denied.getMessage().contains("compiler-owned"),
                denied::getMessage);
        // Even an exact, already-vetted map supplied by the host may not
        // mint ownership permissions. The checker must re-run itself.
        var exact = java.util.Map.of(task, java.util.Map.of(
                "value", Ast.TaskCaptureTransfer.COPY));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(checked, attempt, exact));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(
                        checked, attempt, exact, null));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.requireProgramEpoch(checked));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(checked, task, revoked));

        // A new full checker pass is the only authorizing path. The epoch
        // is newer than the test-only synthetic attempt.
        OwnershipChecker.check(checked);
        long authorized = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertTrue(authorized > attempt);
        assertEquals(exact.get(task),
                Ast.CheckedTaskCaptures.require(
                        checked, task, authorized).captureTransfers());
    }

    @Test
    void supersededConcurrentCheckerCannotRepublishStaleTaskAuthority() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub suspends fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value; };
                  stdio.stdout.write(await child);
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(checked);
        long first = Ast.CheckedTaskCaptures.newCheckEpoch();
        Ast.CheckedTaskCaptures.beginCheck(checked, first);
        long replacement = Ast.CheckedTaskCaptures.newCheckEpoch();
        Ast.CheckedTaskCaptures.beginCheck(checked, replacement);

        java.util.Map<Ast.TaskExpr,
                java.util.Map<String, Ast.TaskCaptureTransfer>> captures =
                java.util.Map.of(task, java.util.Map.of(
                        "value", Ast.TaskCaptureTransfer.COPY));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(
                        checked, first, captures));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(
                        checked, replacement, captures));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.requireProgramEpoch(checked));

        // Later full ownership analysis supersedes both synthetic attempts
        // without permitting public callers to mint an arbitrary transfer.
        OwnershipChecker.check(checked);
        long authorized = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertTrue(authorized > replacement);
        assertEquals(captures.get(task),
                Ast.CheckedTaskCaptures.require(
                        checked, task, authorized).captureTransfers());
    }

    @Test
    void rawHostAstAndForgedEpochCannotPublishTaskCaptures() {
        Ast.Program raw = Parser.parse("""
                pub suspends fnc main(): void {
                  const int value = 9;
                  let Future<int> child = task { return value; };
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(raw);
        long forged = Ast.CheckedTaskCaptures.newCheckEpoch();
        Ast.CheckedTaskCaptures.beginCheck(raw, forged);
        var claimed = java.util.Map.of(task,
                java.util.Map.of("value", Ast.TaskCaptureTransfer.MOVE));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(raw, forged, claimed));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.publish(raw, forged, claimed, null));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.requireProgramEpoch(raw));
        for (var constructor : OwnershipChecker.TaskPublishAuthority.class
                .getDeclaredConstructors()) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(
                    constructor.getModifiers()));
        }
    }

    @Test
    void untypedNoncriticalTaskCannotAcquireExecutableOwnership() {
        Ast.Program raw = Parser.parse("""
                pub suspends fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(raw);
        IllegalStateException denied = assertThrows(IllegalStateException.class,
                () -> OwnershipChecker.check(raw));
        assertTrue(denied.getMessage().contains(
                "successfully type-checked exact Program"), denied::getMessage);
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.requireProgramEpoch(raw));

        Ast.Program typed = TypeChecker.checkTypes(raw);
        OwnershipChecker.check(typed);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(typed);
        assertEquals(java.util.Map.of("value", Ast.TaskCaptureTransfer.COPY),
                Ast.CheckedTaskCaptures.require(
                        typed, firstTask(typed), epoch).captureTransfers());
    }

    @Test
    void successfulTypeRecheckRevokesPreviouslyExecutableNoncriticalTask() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub suspends fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(checked);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertDoesNotThrow(() -> Ast.CheckedTaskCaptures.require(checked, task, epoch));
        long typingBefore = TypeChecker.requireCriticalTypeProof(checked);

        Ast.Program retyped = TypeChecker.checkTypes(checked);
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(checked, task, epoch),
                "successful retyping must not revive the old Task ownership proof");
        if (retyped == checked) {
            assertNotEquals(typingBefore, TypeChecker.requireCriticalTypeProof(checked));
        }
        OwnershipChecker.check(retyped);
        long refreshedEpoch = Ast.CheckedTaskCaptures.requireProgramEpoch(retyped);
        assertDoesNotThrow(() -> Ast.CheckedTaskCaptures.require(
                retyped, firstTask(retyped), refreshedEpoch));
    }

    @Test
    void failedTypeRecheckRevokesPreviouslyExecutableNoncriticalTask() {
        Ast.Program checked = OresCompiler.parseAndTypeCheck("""
                pub suspends fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  return;
                }
                """);
        Ast.TaskExpr task = firstTask(checked);
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertDoesNotThrow(() -> Ast.CheckedTaskCaptures.require(checked, task, epoch));

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                        checked, java.util.Map.of(), java.util.Map.of(), null));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedTaskCaptures.require(checked, task, epoch));
        assertThrows(IllegalStateException.class,
                () -> OwnershipChecker.check(checked));
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

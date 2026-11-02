package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Critical/suspends v1 source-admission contract.
 *
 * These are compiler admission tests, NOT proof of critical runtime lowering.
 * A critical source is rejected until admission, scoped guard release and
 * transitive no-suspension checking are wired to the executable AST.
 */
final class CriticalSuspendsV1SyntaxTest {
    private static void rejectsWith(String source, String message) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source), source);
        assertTrue(error.getMessage().contains(message), error::toString);
    }

    @Test void canonicalSynchronousAndSuspendingFunctionsRemainAccepted() {
        assertDoesNotThrow(() -> Parser.parse("pub fnc sync(): int { return 7; }"));
        assertDoesNotThrow(() -> Parser.parse("pub suspends fnc work(): int { return 7; }"));
        assertDoesNotThrow(() -> Parser.parse("pub suspends routine work(): int { return 7; }"));
    }

    @Test void asyncIsNotAnAliasOfSuspends() {
        rejectsWith("async fnc work(): int { return 7; }", "'async' callable modifier is removed");
        rejectsWith("pub async routine work(): int { return 7; }", "'async' callable modifier is removed");
        rejectsWith("suspends async fnc work(): int { return 7; }", "'async' callable modifier is removed");
    }

    @Test void criticalAndSuspendsCannotBothModifyACallable() {
        rejectsWith("critical suspends fnc work(): void { return; }",
                "'critical suspends' is forbidden");
        rejectsWith("suspends critical fnc work(): void { return; }",
                "'critical suspends' is forbidden");
        rejectsWith("pub critical suspends routine work(): void { return; }",
                "'critical suspends' is forbidden");
    }

    @Test void unloweredCriticalCallableNeverExecutesWithoutExclusion() {
        rejectsWith("pub critical fnc work(): void { return; }",
                "critical callable requires");
    }

    @Test void criticalBlockHasDistinctAstAndRequiresActorOwnedGate() {
        String async = "pub suspends fnc work(): void { critical { return; } }";
        Ast.FunctionDecl parsed = (Ast.FunctionDecl) Parser.parse(async)
                .modules().getFirst().declarations().getFirst();
        Ast.CriticalStmt critical = assertInstanceOf(
                Ast.CriticalStmt.class, parsed.body().getFirst());
        assertEquals(1, critical.body().size());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(async));
        assertTrue(failure.getMessage().contains(
                "critical block requires a suspending actor method with actor-owned gate"), failure::toString);

        String sync = "pub fnc work(): void { critical { return; } }";
        assertInstanceOf(Ast.CriticalStmt.class,
                ((Ast.FunctionDecl) Parser.parse(sync).modules().getFirst()
                        .declarations().getFirst()).body().getFirst());
        failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(sync));
        assertTrue(failure.getMessage().contains(
                "critical block may suspend on entry"), failure::toString);
    }

    @Test void checkedActorCriticalBodyRequiresSuspendingMethodAndNoUnverifiedEffects() {
        String actor = """
                define actor Worker as
                  suspends receive(ActorMail<String> mail): void {
                    let int value = 40;
                    critical { value = value + 2; }
                    return;
                  }
                end
                """;
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck(actor));
        Ast.ClassDecl worker = (Ast.ClassDecl) checked.modules().getFirst()
                .declarations().getFirst();
        Ast.CriticalStmt block = assertInstanceOf(Ast.CriticalStmt.class,
                worker.methods().getFirst().body().get(1));
        long epoch = Ast.CheckedTaskCaptures.requireProgramEpoch(checked);
        assertDoesNotThrow(
                () -> Ast.CheckedCriticalBlocks.require(checked, block, epoch));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedCriticalBlocks.require(
                        checked, new Ast.CriticalStmt(block.body()), epoch));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedCriticalBlocks.require(
                        Parser.parse(actor), block, epoch));

        Ast.Program rechecked = dev.oreslang.types.OwnershipChecker.check(checked);
        assertSame(checked, rechecked);
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedCriticalBlocks.require(checked, block, epoch),
                "recheck must revoke old critical permissions");
    }

    @Test void actorCriticalRejectsSuspensionAndUnknownHostEffects() {
        for (String unsafe : java.util.List.of(
                "await task;",
                "unknown();",
                "rt io_read();",
                "critical { return; }")) {
            String actor = """
                    define actor Worker as
                      suspends receive(ActorMail<String> mail): void {
                        critical { """ + unsafe + """ 
                        }
                        return;
                      }
                    end
                    """;
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(actor), unsafe);
        }
    }

    @Test void tryCriticalIsDistinctFromTryCatchAndRequiresElse() {
        String source = "pub fnc work(): void { try critical { return; } else { return; } }";
        Ast.FunctionDecl fn = (Ast.FunctionDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        Ast.TryCriticalStmt guarded = assertInstanceOf(
                Ast.TryCriticalStmt.class, fn.body().getFirst());
        assertEquals(1, guarded.body().size());
        assertEquals(1, guarded.elseBody().size());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains(
                "try critical requires immediate nonblocking ticket"), failure::toString);
        rejectsWith("pub fnc work(): void { try critical { return; } }",
                "try critical requires 'else");
    }

    @Test void CriticalWordsInCommentsDoNotChangeExecution() {
        assertDoesNotThrow(() -> Parser.parse("""
                // async fnc and critical suspends are deprecated source spellings
                pub suspends fnc work(): int {
                    // critical { await read() }
                    return 1;
                }
                """));
    }
}

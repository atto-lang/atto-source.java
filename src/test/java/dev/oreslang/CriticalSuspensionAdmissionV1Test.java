package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CriticalSuspensionAdmissionV1Test {
    private static void rejected(String src, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse(src));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test void declarationOrdersRejectUnsoundCriticalSuspends() {
        rejected("critical suspends fnc wrong(): void { return; }", "E_CRITICAL_SUSPENDS_DECL");
        rejected("suspends critical fnc wrong(): void { return; }", "E_CRITICAL_SUSPENDS_DECL");
        rejected("pub suspends critical fnc wrong(): void { return; }", "E_CRITICAL_SUSPENDS_DECL");
        rejected("define module app\n pub critical suspends fnc wrong(): void { return; }\nend",
                "E_CRITICAL_SUSPENDS_DECL");
    }

    @Test void criticalCallableRequiresRealRuntimeLowering() {
        rejected("critical fnc run(): void { return; }", "E_CRITICAL_UNLOWERED");
        rejected("pub critical routine run(): void { return; }", "E_CRITICAL_UNLOWERED");
    }

    private static dev.oreslang.ast.Ast.CriticalStmt statement(String source) {
        var ast = Parser.parse(source);
        var fn = (dev.oreslang.ast.Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
        return (dev.oreslang.ast.Ast.CriticalStmt) fn.body().getFirst();
    }

    @Test void criticalBlocksHaveDedicatedUnloweredAstAndTypeGate() {
        var plain = statement("suspends fnc run(): void { critical { return; } }");
        assertFalse(plain.tryOnly());
        assertNull(plain.gateName());
        assertEquals(1, plain.body().size());

        var named = statement("suspends fnc run(): void { critical gate { return; } }");
        assertFalse(named.tryOnly());
        assertEquals("gate", named.gateName());

        var immediate = statement("fnc run(): void { try critical { return; } else { return; } }");
        assertTrue(immediate.tryOnly());
        assertNull(immediate.gateName());
        assertEquals(1, immediate.elseBody().size());

        var namedImmediate = statement("fnc run(): void { try critical gate { return; } else { return; } }");
        assertTrue(namedImmediate.tryOnly());
        assertEquals("gate", namedImmediate.gateName());

        for (String source : new String[] {
            "suspends fnc run(): void { critical { return; } }",
            "suspends fnc run(): void { critical gate { return; } }",
            "fnc run(): void { try critical { return; } else { return; } }",
            "fnc run(): void { try critical gate { return; } else { return; } }"
        }) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(source));
            assertTrue(error.getMessage().contains("E_CRITICAL_UNLOWERED"), error.getMessage());
        }
    }

    @Test void tryCriticalRequiresExplicitFallbackAndCannotBeMisparsedAsTryCatch() {
        rejected("fnc run(): void { try critical { return; } }", "try critical requires an explicit else");
        rejected("fnc run(): void { try critical gate { return; } catch(e) { return; } }",
                "try critical requires an explicit else");
    }

    @Test void lexerDoesNotTreatCommentsAndStringsAsCriticalSyntax() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc critical(): int { return 7; }
                fnc run(): void {
                    // critical { await slow(); }
                    val x = "critical suspends fnc";
                    critical();
                    return;
                }
                """));
    }

    @Test void existingAsyncGeneratorAndSuspendsRemainAdmittedForMigration() {
        assertDoesNotThrow(() -> Parser.parse("""
                suspends fnc helper(): int { return 7; }
                async generator fnc numbers(): int { yield 1; return; }
                """));
    }
}

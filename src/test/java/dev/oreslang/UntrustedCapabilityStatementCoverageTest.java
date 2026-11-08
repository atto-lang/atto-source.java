package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-parser + capability-checker coverage. These tests intentionally do
 * not imply that unsafe task/borrow forms can execute in UNTRUSTED actors.
 * Runtime and type-checker gates remain independent and must stay closed.
 */
final class UntrustedCapabilityStatementCoverageTest {
    private static Ast.Program untrusted(String body) {
        return Parser.parse("pub untrusted actor fnc worker(): void {\n"
                + body + "\nreturn;\n}\n");
    }

    private static void deny(String body, String expected) {
        Ast.Program program = untrusted(body);
        SecurityException failure = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(failure.getMessage().contains(expected), failure::getMessage);
    }

    @Test
    void taskScopeDeadlineCannotReadProcessAuthority() {
        Ast.Program parsed = untrusted("""
                task scope deadline(process.context_id) {
                  let marker = 1;
                }
                """);
        var declaration = (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
        assertInstanceOf(Ast.TaskScopeStmt.class, declaration.body().getFirst());
        deny("""
                task scope deadline(process.context_id) {
                  let marker = 1;
                }
                """, "PROCESS_INFO");
    }

    @Test
    void taskScopeBodyCannotLaunderRestrictedCapability() {
        deny("""
                task scope {
                  let forbidden = process.context_id;
                }
                """, "PROCESS_INFO");
    }

    @Test
    void usingBorrowBodyMustBeCheckedEvenIfRuntimeLoweringIsClosed() {
        deny("""
                using request.borrow() as view {
                  let forbidden = process.context_id;
                }
                """, "PROCESS_INFO");
    }

    @Test
    void usingBorrowResourceMustBeCheckedBeforeItsBody() {
        deny("""
                using process.borrow() as view {
                  let ordinary = 1;
                }
                """, "restricted capability facade");
    }

    @Test
    void localTypeAliasesCannotHideSharedMemoryAuthority() {
        deny("""
                type Secret = SharedMutex<int>;
                """, "SHARED_MEMORY");
    }

    @Test
    void ordinaryPureStatementsStayAdmissibleUnderZeroAuthority() {
        assertDoesNotThrow(() -> CapabilityChecker.check(untrusted("""
                type LocalNumber = int;
                task scope deadline(25) {
                  let number = 7;
                }
                do {
                  let value = 9;
                } while (false);
                """), IsolatePolicy.developer()));
    }
}

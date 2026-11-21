package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Canonical task {} syntax; only immediate awaited root scalar tasks have guest lowering. */
final class TaskBlockSyntaxTest {
    @Test
    void parsesFireAndContinueAsStatement() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                    do task {
                        let int value = 7;
                    };
                    return;
                }
                """));
        assertNotNull(program);
    }

    @Test
    void parsesAwaitableTaskAsExpression() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                    let Task<int> computation = task {
                        return 42;
                    };
                    let int answer = await computation;
                    return;
                }
                """));
        assertNotNull(program);
    }

    @Test
    void supportsVoidCompletionAndNestedAwaitInTaskBody() {
        assertDoesNotThrow(() -> Parser.parse("""
                async fnc operation(): int { return 2; }
                pub fnc main(): void {
                    let Task<void> work = task {
                        let int v = await operation();
                    };
                    await work;
                    return;
                }
                """));
    }

    @Test
    void rejectsBareTaskStatementInsteadOfSilentlyDiscardingHandle() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main(): void {
                            task { return; }
                            return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("do task"), failure::getMessage);
    }

    @Test
    void rejectsTaskExecutionUntilSafeLoweringExists() {
        for (String body : new String[]{
                "do task { let int n = 1; };",
                "let Task<int> t = task { return 1; };",
                "let Task<void> t = task { return; };"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "pub fnc main(): void { " + body + " return; }"));
            assertTrue(failure.getMessage().contains("Task blocks"), failure::getMessage);
        }
    }

    @Test
    void taskKeywordsInsideCommentsAndStringsHaveNoEffect() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc message(): string {
                    // do task { dangerous(); }
                    return "nb { } do task { }";
                }
                """));
    }
}

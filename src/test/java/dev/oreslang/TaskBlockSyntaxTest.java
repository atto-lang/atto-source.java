package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Syntax admission only: Task scheduling and capture-safe lowering remain gated. */
final class TaskBlockSyntaxTest {
    @Test
    void parsesFireAndContinueAsStatement() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): void {
                    do nb {
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
                    let Task<int> computation = nb {
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
                    let Task<void> work = nb {
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
                            nb { return; }
                            return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("do nb"), failure::getMessage);
    }

    @Test
    void rejectsTaskExecutionUntilSafeLoweringExists() {
        for (String body : new String[]{
                "do nb { let int n = 1; };",
                "let Task<int> t = nb { return 1; };",
                "let Task<void> t = nb { return; };"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "pub fnc main(): void { " + body + " return; }"));
            assertTrue(failure.getMessage().contains("nb Task blocks"), failure::getMessage);
        }
    }

    @Test
    void taskKeywordsInsideCommentsAndStringsHaveNoEffect() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc message(): string {
                    // do nb { dangerous(); }
                    return "nb { } do nb { }";
                }
                """));
    }
}

package dev.oreslang;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExplicitTaskPayloadForwardPortTest {
    private static final String SOURCE = "pub suspends fnc main(): int { val Future<int> pending = task<int> { return 42; }; return await pending; }";
    @Test void checkedExplicitPayloadAndContext() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(SOURCE));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("task<int>", "task<bool>")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("Future<int>", "Future<bool>")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("return 42;", "return true;")));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(SOURCE.replace("task<int>", "task<Future<int>>")));
    }
    @Test void contextualTaskNameRemainsCallable() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("fnc task(int n): int { return n; } pub fnc main(): int { return task(42); }"));
    }
}

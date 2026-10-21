package dev.oreslang;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class ImplicitLoopBorrowBudgetTest {
    @Test void unannotatedAwaitAndTransitiveCallsCannotHideSourceTaskEffects() {
        String base = """
                async fnc one(): int { return 1; }
                fnc hidden(): int { return await one(); }
                fnc transit(): int { return hidden(); }
                fnc read(borrow {value: int} data, int n): int { return data.value + n; }
                """;
        for (String call : new String[]{"hidden()", "transit()"}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(base + "fnc bad(): void { val data=struct{value:int}{value:7}; read(rt borrow data, " + call + "); return; }"));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(base + "fnc bad(): void { val data=struct{value:int}{value:7}; val view=rt borrow data; for(let i=0;i<2000;i=i+1) { stdio.println(view.value); } transit(); return; }"));
        assertTrue(error.getMessage().contains("automatic loop"), error.getMessage());
    }
}

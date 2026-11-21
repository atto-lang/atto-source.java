package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A readonly structural view must never launder an exclusive capability. */
final class StructuralMutableCapabilityTest {
    private static final String PRELUDE = """
            define class Widget as
              pub let int id = 7;
            end
            """;

    @Test
    void mutableLoanCannotBePassedAsAnImplicitReadonlyStructuralView() {
        for (String signature : new String[] {
                "struct{id: int} view",
                "view: struct{id: int}",
                "structural struct{id: int} view",
                "view: structural struct{id: int}"
        }) {
            String source = PRELUDE
                    + "fnc inspect(" + signature + "): int { return view.id; }\n"
                    + "fnc bad(): int { let Widget w = new Widget(); "
                    + "return inspect(rt mut borrow w); }";
            var parsed = assertDoesNotThrow(() -> Parser.parse(source), source);
            IllegalArgumentException standalone = assertThrows(
                    IllegalArgumentException.class, () -> OwnershipChecker.check(parsed), source);
            assertTrue(standalone.getMessage().contains("mutable borrow"),
                    standalone.getMessage());
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed), source);
        }
    }

    @Test
    void ordinaryReadonlyValueDoesNotRequireExclusiveBorrow() {
        String source = PRELUDE + """
                fnc inspect(struct{id: int} view): int { return view.id; }
                fnc good(): int {
                  val Widget w = new Widget();
                  return inspect(w);
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed));
    }
}

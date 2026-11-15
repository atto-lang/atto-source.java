package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real Java parser/ownership checker fixtures; executable guest parity remains a separate gate. */
final class OwnershipBangAliasesTest {
    private static Ast.Program source(String expression) {
        return Parser.parse("fnc main(): int { val int foo = 7; return " + expression + "; }");
    }

    private static void sameAst(String legacy, String spaced, String compact) {
        Ast.Program expected = source(legacy);
        assertEquals(expected, source(spaced), spaced);
        assertEquals(expected, source(compact), compact);
    }

    @Test
    void aliasesShareTheLegacyOwnershipAst() {
        sameAst("rt copy foo", "copy! foo", "copy!foo");
        sameAst("rt take foo", "take! foo", "take!foo");
        sameAst("rt borrow foo", "borrow! foo", "borrow!foo");
        sameAst("rt share foo", "share! foo", "share!foo");
        sameAst("rt mut borrow foo", "mut borrow! foo", "mut borrow!foo");
        assertEquals(source("rt mut borrow foo"), source("rt borrow mut foo"),
                "both historic mutable-borrow word orders must agree");
        sameAst("rt copy(foo + 1)", "copy! (foo + 1)", "copy!(foo + 1)");
        sameAst("rt borrow foo.bar", "borrow! foo.bar", "borrow!foo.bar");
    }

    @Test
    void rejectsWhitespaceBeforeBangRatherThanReinterpretingItAsOwnership() {
        for (String invalid : new String[]{"copy !foo", "take !foo", "borrow !foo",
                "mut borrow !foo", "share !foo", "copy!", "mut borrow!"}) {
            assertThrows(RuntimeException.class, () -> source(invalid), invalid);
        }
    }

    @Test
    void inequalityAndLogicalNegationKeepTheirExistingTokenization() {
        assertDoesNotThrow(() -> Parser.parse("fnc main(): bool { val bool copy = false; " +
                "val bool foo = true; return copy != foo; }"));
        assertDoesNotThrow(() -> Parser.parse("fnc main(): bool { val bool foo = false; " +
                "return !foo; }"));
    }

    @Test
    void bangBorrowRunsTheExistingOwnershipChecker() {
        String base = """
                define class Item as
                  pub val int value = 42;
                end
                fnc read_item(borrow Item item): int {
                  return item.value;
                }
                pub fnc main(): int {
                  val Item item = new Item();
                  return read_item(%s);
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(base.formatted("borrow!item")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(base.formatted("borrow! item")));
    }

    @Test
    void bangMutBorrowDoesNotBypassAliasingCheck() {
        String source = """
                define class Item as
                  pub let String value = "one";
                end
                fnc read_it(): void {
                  let Item item = new Item();
                  val borrow mut Item first = mut borrow!item;
                  val borrow mut Item second = mut borrow! item;
                  stdio.println(first.value);
                  stdio.println(second.value);
                  return;
                }
                """;
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error::getMessage);
    }
}

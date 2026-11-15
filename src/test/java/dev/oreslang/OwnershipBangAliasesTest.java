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
        // 'eqfoo' is an identifier: only the complete '!eq' word is atomic.
        String eqPrefix = "fnc main(): int { val int eqfoo = 7; return %s; }";
        assertEquals(Parser.parse(eqPrefix.formatted("rt copy eqfoo")),
                     Parser.parse(eqPrefix.formatted("copy!eqfoo")));
        sameAst("rt take foo", "take! foo", "take!foo");
        sameAst("rt borrow foo", "borrow! foo", "borrow!foo");
        // Without a token boundary, mutfoo is an ordinary identifier and the
        // operation remains immutable Borrow, not BorrowMut.
        sameAst("rt borrow mutfoo", "borrow! mutfoo", "borrow!mutfoo");
        sameAst("rt share foo", "share! foo", "share!foo");
        sameAst("rt mut borrow foo", "mut borrow! foo", "mut borrow!foo");
        sameAst("rt mut borrow foo", "borrow! mut foo", "borrow!mut foo");
        // Trivia after the adjacent bang must not silently downgrade BorrowMut.
        sameAst("rt mut borrow foo", "borrow!\nmut foo",
                "borrow!/* checked loan */mut foo");
        sameAst("rt mut borrow(foo)", "borrow! mut (foo)", "borrow!mut(foo)");
        sameAst("rt borrow mut foo", "borrow! mut foo", "borrow!mut foo");
        sameAst("rt mut borrow foo.bar", "borrow! mut foo.bar", "borrow!mut foo.bar");
        assertThrows(RuntimeException.class, () -> source("mut borrow! mut foo"),
                "two mutable borrow qualifiers must not be admitted");
        assertEquals(source("rt mut borrow foo"), source("rt borrow mut foo"),
                "both historic mutable-borrow word orders must agree");
        sameAst("rt copy(foo + 1)", "copy! (foo + 1)", "copy!(foo + 1)");
        sameAst("rt borrow foo.bar", "borrow! foo.bar", "borrow!foo.bar");
    }

    @Test
    void unicodeBmpIdentifierAfterEqPrefixDoesNotBecomeInequality() {
        // Java's lexical identifier continuation admits Unicode letters.
        // '!eq' is atomic only at the actual identifier boundary, not at
        // JavaScript's narrower ASCII \\b word boundary.
        String program = "fnc main(): int { val int eqé = 7; return %s; }";
        assertEquals(Parser.parse(program.formatted("rt copy eqé")),
                Parser.parse(program.formatted("copy!eqé")));
        String neq = "fnc main(): bool { val int copy = 1; " +
                "val int é = 2; return %s; }";
        assertEquals(Parser.parse(neq.formatted("copy neq é")),
                Parser.parse(neq.formatted("copy!eq é")));
    }

    @Test
    void rejectsWhitespaceBeforeBangRatherThanReinterpretingItAsOwnership() {
        for (String invalid : new String[]{"copy !foo", "take !foo", "borrow !foo",
                "mut borrow !foo", "share !foo", "copy!", "mut borrow!",
                "borrow! mut", "borrow!mut", "borrow! mut mut foo",
                "mut borrow! mut foo"}) {
            assertThrows(RuntimeException.class, () -> source(invalid), invalid);
        }
    }

    @Test
    void doubleMutableQualifiersReceiveTargetedDiagnostic() {
        for (String malformed : new String[]{
                "borrow! mut mut foo",
                "borrow!mut mut foo",
                "mut borrow! mut foo",
                "rt borrow mut mut foo",
                "rt mut borrow mut foo"}) {
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> source(malformed), malformed);
            assertTrue(error.getMessage().contains("duplicate mutable borrow qualifier"),
                    malformed + ": " + error.getMessage());
        }
    }

    @Test
    void inequalityAndLogicalNegationKeepTheirExistingTokenization() {
        assertDoesNotThrow(() -> Parser.parse("fnc main(): bool { val bool copy = false; " +
                "val bool foo = true; return copy != foo; }"));
        // AttoLang !eq is a *single inequality token* at an identifier
        // boundary and must not be stolen by the ownership-prefix parser.
        Ast.Program inequality = Parser.parse("fnc main(): bool { val int copy = 1; " +
                "val int foo = 2; return copy!eq foo; }");
        assertEquals(Parser.parse("fnc main(): bool { val int copy = 1; " +
                "val int foo = 2; return copy neq foo; }"), inequality);
        assertDoesNotThrow(() -> Parser.parse("fnc main(): bool { val bool foo = false; " +
                "return !foo; }"));
    }

    @Test
    void deepPrefixChainsFailDeterministicallyInsteadOfOverflowingTheJvmStack() {
        for (String prefix : new String[]{"copy!", "borrow!", "take!",
                "share!", "mut borrow!", "!"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> source(prefix.repeat(200) + "foo"), prefix);
            assertTrue(error.getMessage().contains("unary expression nesting exceeds 128"),
                    prefix + ": " + error.getMessage());
        }
        // The same boundary applies to legacy spelling, rather than allowing
        // an attacker to avoid the guard with a mixture of aliases.
        IllegalArgumentException legacy = assertThrows(IllegalArgumentException.class,
                () -> source("rt take ".repeat(200) + "foo"));
        assertTrue(legacy.getMessage().contains("unary expression nesting exceeds 128"));
    }

    @Test
    void nestedCommentsCannotLeakOwnershipAliasesOrExceedLexerBudget() {
        assertDoesNotThrow(() -> Parser.parse(
                "fnc main(): int { val int foo = 7; return " +
                "/* outer /* borrow! mut foo */ tail /* take!foo */ */ copy!foo; }"));
        RuntimeException unclosed = assertThrows(RuntimeException.class,
                () -> Parser.parse(
                        "fnc main(): int { return /* outer /* copy!7 */ 7; }"));
        assertTrue(unclosed.getMessage().contains("unterminated block comment"),
                unclosed::getMessage);
        String excessive = "fnc main(): int { return " +
                "/*".repeat(129) + "copy!7" + "*/".repeat(129) + "7; }";
        RuntimeException tooDeep = assertThrows(RuntimeException.class,
                () -> Parser.parse(excessive));
        assertTrue(tooDeep.getMessage().contains("block comment nesting exceeds 128"),
                tooDeep::getMessage);
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
    void bangCopyAndShareCannotCloneMoveOnlyOwners() {
        String source = """
                define class Item as
                  pub val int value = 42;
                end
                fnc main(): int {
                  val Item item = new Item();
                  val Item cloned = %s;
                  return cloned.value;
                }
                """;
        for (String operation : new String[]{"copy", "share"}) {
            for (String spelling : new String[]{"rt " + operation + " item",
                    operation + "! item", operation + "!item"}) {
                RuntimeException error = assertThrows(RuntimeException.class,
                        () -> OresCompiler.parseAndTypeCheck(source.formatted(spelling)),
                        spelling);
                assertTrue(error.getMessage().contains("move-only")
                                || error.getMessage().contains("non-Copy"),
                        spelling + ": " + error.getMessage());
            }
        }
    }

    @Test
    void bangTakeMustInvalidateTheMovedOwner() {
        String source = """
                define class Item as
                  pub val int value = 42;
                end
                fnc main(): int {
                  let Item item = new Item();
                  val Item moved = %s;
                  return item.value;
                }
                """;
        for (String spelling : new String[]{"rt take item", "take! item", "take!item"}) {
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> OresCompiler.parseAndTypeCheck(source.formatted(spelling)),
                    spelling);
            assertTrue(error.getMessage().toLowerCase().contains("mov"),
                    spelling + ": " + error.getMessage());
        }
    }

    @Test
    void bangBorrowCannotEscapeItsLocalLifetime() {
        String source = """
                define class Item as
                  pub val int value = 42;
                end
                fnc leak(): borrow Item {
                  let Item item = new Item();
                  return %s;
                }
                """;
        for (String spelling : new String[]{"rt borrow item", "borrow! item", "borrow!item"}) {
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> OresCompiler.parseAndTypeCheck(source.formatted(spelling)),
                    spelling);
            assertTrue(error.getMessage().toLowerCase().contains("outlive")
                            || error.getMessage().toLowerCase().contains("borrow"),
                    spelling + ": " + error.getMessage());
        }
    }

    @Test
    void contiguousInequalityIsNotAnOwnershipPrefix() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "fnc main(): bool { val int copy = 1; return copy!eq 2; }"));
    }

    @Test
    void bangMutBorrowDoesNotBypassAliasingCheck() {
        String source = """
                define class Item as
                  pub let String value = "one";
                end
                fnc read_it(): void {
                  let Item item = new Item();
                  val borrow mut Item first = borrow! mut item;
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

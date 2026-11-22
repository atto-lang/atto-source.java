package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser admission guards shared by static blocking, procedural and nonblocking forms. */
final class SelectFormParserGuardTest {
    private static final String[] BRACED_FORMS = {
            "select", "do select", "nb select", "do nb select"
    };

    private static final String[] OVER_FORMS = {
            "select over", "do select over", "nb select over", "do nb select over"
    };

    @Test
    void rejectsEmptySelectionsAtParserBoundary() {
        for (String form : BRACED_FORMS) {
            String source = "pub fnc main(): void { " + form + " {} }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), form);
            assertTrue(failure.getMessage().contains("select requires at least one arm"),
                    failure::getMessage);
        }
    }

    @Test
    void rejectsDuplicateDefaultsBeforeBuildingAst() {
        for (String form : BRACED_FORMS) {
            String source = "pub fnc main(): void { " + form
                    + " { default: {} default: {} } }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), form);
            assertTrue(failure.getMessage().contains("select permits at most one default arm"),
                    failure::getMessage);
        }
    }
    @Test
    void rejectsEmptyOverEndSelectionsAtParserBoundary() {
        for (String form : OVER_FORMS) {
            String source = "pub fnc main(): void { " + form + " end }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), form);
            assertTrue(failure.getMessage().contains("select requires at least one arm"),
                    failure::getMessage);
        }
    }

    @Test
    void rejectsDuplicateDefaultsInOverEndSelections() {
        for (String form : OVER_FORMS) {
            String source = "pub fnc main(): void { " + form
                    + " default: {} default: {} end }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), form);
            assertTrue(failure.getMessage().contains("select permits at most one default arm"),
                    failure::getMessage);
        }
    }

    @Test
    void amongRemainsRejectedUntilItIsExplicitlyAddedToTheGrammar() {
        for (String form : new String[] {"select", "do select", "nb select"}) {
            String source = "pub fnc main(): void { " + form
                    + " among default: {} end }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), form);
        }
    }

    @Test
    void rejectsArmsAfterDefaultInBracedSelections() {
        for (String form : BRACED_FORMS) {
            String source = "fnc bad(Channel<int> ch): void { " + form
                    + " { default: {} when readch ch: val value {} } return; }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), form);
            assertTrue(failure.getMessage().contains("select default arm must be last"),
                    failure::getMessage);
        }
    }

    @Test
    void rejectsArmsAfterDefaultInOverEndSelections() {
        for (String form : OVER_FORMS) {
            String source = "fnc bad(Channel<int> ch): void { " + form
                    + " default: {} when readch ch: val value {} end return; }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), form);
            assertTrue(failure.getMessage().contains("select default arm must be last"),
                    failure::getMessage);
        }
    }


    @Test
    void overDelimiterDoesNotAdmitDoTrySelect() {
        String source = """
                pub fnc main(): void {
                  do try select over
                    default: {}
                  end
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("'do try select' is not supported"),
                failure::getMessage);
    }


}

package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FinallySelectReturnBoundaryTest {
    @Test
    void doSelectReturnWithinFinallyCannotBypassLexicalBan() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    fnc invalid(): bool {
                      try { return true; }
                      catch (e) { return false; }
                      finally {
                        do select {
                          default: { return true; }
                        }
                      }
                    }
                    """)));
        assertTrue(failure.getMessage().contains("return is forbidden directly inside finally"),
                failure.getMessage());
    }

    @Test
    void returnInsideAnIndependentLambdaNestedInFinallySelectRemainsLegal() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                fnc allowed(): bool {
                  try { return true; }
                  catch (e) { return false; }
                  finally {
                    do select {
                      default: {
                        (|| -> { return true; })();
                      }
                    }
                  }
                }
                """)));
    }

    @Test
    void armLocalSelectReturnOutsideFinallyRemainsLegal() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                fnc valid(): bool {
                  do select {
                    default: { return true; }
                  }
                  return false;
                }
                """)));
    }
}

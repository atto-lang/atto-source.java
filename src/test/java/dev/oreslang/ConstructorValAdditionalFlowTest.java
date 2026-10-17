package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConstructorValAdditionalFlowTest {
    private static void ok(String source) {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }
    private static void bad(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test void nestedBlockAndCondition() {
        ok("""
            define class C as
              val int x;
              constructor(int n) {
                if n > 0 then
                  { self.x = 1; }
                else
                  { self.x = 2; }
                fi
              }
            end
            """);
    }

    @Test void uninitializedReadInBranchCondition() {
        bad("""
            define class C as
              val int x;
              constructor() {
                if self.x > 0 then
                  self.x = 1;
                else
                  self.x = 2;
                fi
              }
            end
            """);
    }

    @Test void possibleRepeatAfterJoin() {
        bad("""
            define class C as
              val int x;
              constructor(bool a) {
                if a then self.x = 1; fi
                self.x = 2;
              }
            end
            """);
    }
}

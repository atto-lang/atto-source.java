package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RouteContractConformanceSyntaxTest {
    private static final String DECL = """
        define contract RouteContract as
          fnc get(String request) -> String;
          fnc post(String request) -> String;
        end
        """;

    @Test void moduleCanConformWithOrWithoutTo() {
        for (String syntax : new String[] {"conforms RouteContract", "conforms to RouteContract"}) {
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(DECL +
                "define module Route " + syntax + " as\n" +
                "  pub fnc get(String request) -> String { return request; }\n" +
                "  pub fnc post(String request) -> String { return request; }\n" +
                "end\n")));
        }
    }

    @Test void omittedPostViolatesContract() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(DECL + """
            define module Route conforms to RouteContract as
              pub fnc get(String request) -> String { return request; }
            end
            """)));
    }
}

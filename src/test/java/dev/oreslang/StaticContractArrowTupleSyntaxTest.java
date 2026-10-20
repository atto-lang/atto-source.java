package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StaticContractArrowTupleSyntaxTest {
    @Test
    void staticContractUsesArrowSignaturesAndTupleFields() {
        String source = """
                define static contract Factory as
                    info: tuple (int, int, string);
                    fnc create(int id) -> tuple (int, int, string);
                end

                define class Widget implements static Factory as
                    pub static const info: tuple (int, int, string) = (0, 0, "widget");

                    pub static fnc create(int id) -> tuple (int, int, string) {
                        return (1, id, "widget");
                    }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void moduleContractUsesSameArrowAndTupleTypePath() {
        String source = """
                define contract FactoryModule as
                    info: tuple (int, int, string);
                    fnc create(int id) -> tuple (int, int, string);
                end

                define module widgets conforms FactoryModule as
                    pub const tuple (int, int, string) info = (0, 0, "module");
                    pub fnc create(int id) -> tuple (int, int, string) {
                        return (1, id, "module");
                    }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void instanceInterfaceStillRejectsSlimArrowSignature() {
        String source = """
                define interface InstanceFactory
                    fnc create(int id) -> int;
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }

    @Test
    void staticImplementationStillRequiresBracedMethodBody() {
        String source = """
                define class Widget as
                    pub static fnc create(int id) -> int
                        return id
                    end
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }

    @Test
    void deeplyNestedTupleContractTypeUsesNormalRecursiveTypeParser() {
        String source = """
                define static contract ComplexFactory as
                    fnc create(int id) -> Result<Option<tuple (int, string)>, string>;
                end
                """;

        assertDoesNotThrow(() -> Parser.parse(source));
    }
}

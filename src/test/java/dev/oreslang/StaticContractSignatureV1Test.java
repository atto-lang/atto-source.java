package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StaticContractSignatureV1Test {
    @Test
    void publicStaticFunctionSatisfiesStaticContract() {
        String source = """
                define static contract Factory as
                    fnc create(int id) -> tuple (int, string);
                end

                define class Widget implements static Factory as
                    pub static fnc create(int id) -> tuple (int, string) {
                        return (id, "widget");
                    }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void genericStaticContractCanBeSpecializedByNonGenericClass() {
        String source = """
                define static contract Identity<T> as
                    fnc make(T value) -> T;
                end

                define class IntIdentity implements static Identity<int> as
                    pub static fnc make(int value) -> int {
                        return value;
                    }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void instanceMethodDoesNotSatisfyStaticContract() {
        String source = """
                define static contract Factory as
                    fnc create(int id) -> int;
                end

                define class Widget implements static Factory as
                    pub create(int id) -> int {
                        return id;
                    }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void privateStaticFunctionDoesNotSatisfyPublicStaticContractSlot() {
        String source = """
                define static contract Factory as
                    fnc create(int id) -> int;
                end

                define class Widget implements static Factory as
                    static fnc create(int id) -> int {
                        return id;
                    }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void staticContractRequiresImplementsStaticSpelling() {
        String source = """
                define static contract Factory as
                    fnc create(int id) -> int;
                end

                define class Widget implements Factory as
                    pub static fnc create(int id) -> int {
                        return id;
                    }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("implements static"), error.getMessage());
    }

    @Test
    void ordinaryInterfaceCannotBeUsedAsStaticContract() {
        String source = """
                interface Factory
                    fnc create(int id) => int;
                end

                define class Widget implements static Factory as
                    pub static fnc create(int id) -> int {
                        return id;
                    }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void moduleContractCannotBeUsedAsStaticContract() {
        String source = """
                define contract Factory as
                    fnc create(int id) -> int;
                end

                define class Widget implements static Factory as
                    pub static fnc create(int id) -> int {
                        return id;
                    }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void moduleCannotConformToStaticClassContract() {
        String source = """
                define static contract Factory as
                    fnc create(int id) -> int;
                end

                define module widgets conforms Factory as
                    pub fnc create(int id) -> int
                        return id
                    end
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("class static namespace"), error.getMessage());
    }

    @Test
    void staticContractFieldsFailClosedUntilClassStaticStorageExists() {
        String source = """
                define static contract Factory as
                    coordinates: tuple (int, int, string);
                end
                """;

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("static contract fields"), error.getMessage());
    }

    @Test
    void moduleContractFieldsStillSupportContextualTupleTypes() {
        String source = """
                define contract Config as
                    const tuple (int, int, string) coordinates;
                end

                define module config conforms Config as
                    pub const tuple (int, int, string) coordinates = (0, 0, "origin");
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void genericClassStaticContractConformanceFailsClosedInV1() {
        String source = """
                define static contract Factory as
                    fnc create(int id) -> int;
                end

                define class Widget<T> implements static Factory as
                    pub static fnc create(int id) -> int {
                        return id;
                    }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("generic class"), error.getMessage());
    }

    @Test
    void staticContractCannotCrossExtendModuleContractCategory() {
        String source = """
                define contract Base as
                    fnc create(int id) -> int;
                end

                define static contract Factory extends Base as
                    fnc create(int id) -> int;
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void staticImplementationRemainsBraceBodied() {
        String source = """
                define static contract Factory as
                    fnc create(int id) -> int;
                end

                define class Widget implements static Factory as
                    pub static fnc create(int id) -> int
                        return id
                    end
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }
}

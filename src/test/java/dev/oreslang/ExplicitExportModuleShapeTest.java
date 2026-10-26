package dev.oreslang;

import dev.oreslang.compiler.ExportResolver;
import dev.oreslang.compiler.SourceExportTable;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportModuleShapeTest {

    @Test
    void typeCheckerSeesAliasedDefaultPrivateModuleCallable() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                define module Service as
                  export fnc hidden_value as value;
                  fnc hidden_value(): int { return 9; }
                end

                pub fnc main(): int {
                  return Service.value();
                }
                """)));
    }

    @Test
    void moduleAliasDoesNotExposeTheOriginalMemberSpelling() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        define module Service as
                          export fnc hidden_value as value;
                          fnc hidden_value(): int { return 9; }
                        end

                        pub fnc main(): int {
                          return Service.hidden_value();
                        }
                        """)));
        assertTrue(!failure.getMessage().isBlank());
    }

    @Test
    void moduleValueAliasesHaveTheResolvedExternalName() {
        Ast.Program program = Parser.parse("""
                define module Service as
                  export val secret_count as count;
                  val int secret_count = 7;
                end
                """);
        var binding = ExportResolver.resolve(program).bindings("Service").getFirst();
        assertEquals("count", binding.externalName());
        assertEquals("secret_count", binding.localName());
        assertEquals(1, SourceExportTable.localDeclarations(program, binding).size());

        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                define module Service as
                  export val secret_count as count;
                  val int secret_count = 7;
                end
                pub fnc main(): int {
                  return Service.count;
                }
                """)));
    }
    @Test
    void mixedGenericOverloadFamilyCannotBeImportedAsReifiableFunctionValue() {
        Ast.Program program = Parser.parse("""
                export fnc mixed as public_mixed;
                fnc mixed(int value): int { return value; }
                fnc mixed<T>(T value, int salt): int { return salt; }
                """);
        assertEquals(1, ExportResolver.resolve(program)
                .bindings(Parser.ROOT_MODULE).size());
        assertTrue(SourceExportTable.matchingPublic(
                program, Ast.ImportKind.FUNCTION, "public_mixed").isEmpty(),
                "a partly reifiable exported overload family must fail closed");
    }

}

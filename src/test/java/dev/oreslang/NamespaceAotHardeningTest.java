package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class NamespaceAotHardeningTest {

    @Test
    void defineAndDeclareNamespacesAreFlatTypeOnlyContainers() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define namespace domain as
                  type Id = int;

                  define interface Named
                    String name;
                  end

                  define abstract class Base as
                  end

                  define class User extends Base implements Named as
                    pub val String name;
                  end
                end

                declare namespace transport as {
                  type Port = int;

                  define interface Addressed
                    String host;
                  end
                }

                pub fnc main(): void {
                  val domain.User user = new domain.User();
                  return;
                }
                """));

        assertEquals(Ast.ContainerKind.NAMESPACE, program.modules().getFirst().kind());
        assertEquals(Ast.ContainerKind.NAMESPACE, program.modules().get(1).kind());
        assertEquals(Ast.ContainerKind.MODULE, program.modules().getLast().kind());
    }

    @Test
    void namespaceRejectsRuntimeDeclarationsAndNesting() {
        assertNamespaceRejects("""
                define namespace bad as
                  fnc run(): void { return; }
                end
                """);

        assertNamespaceRejects("""
                define namespace bad as
                  let int state = 0;
                end
                """);

        assertNamespaceRejects("""
                define namespace bad as
                  actor Worker {
                  }
                end
                """);

        assertNamespaceRejects("""
                define namespace outer as
                  define namespace inner as
                  end
                end
                """);

        assertNamespaceRejects("""
                define namespace outer as
                  define module inner
                  end
                end
                """);
    }

    @Test
    void declarationContainersCannotBeDefinedDynamically() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc run(): void {
                  define class Local as
                  end
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc run(): void {
                  define module local
                  end
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc run(): void {
                  define namespace local as
                  end
                  return;
                }
                """));
    }

    @Test
    void dynamicInstancesAndDynamicStructDataRemainAotFriendly() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Foo as
                end

                fnc build(int count): DynamicStruct<Foo> {
                  let DynamicStruct<Foo> values = new DynamicStruct<Foo>();
                  for (let i = 0; i < count; i = i + 1) {
                    values["last"] = new Foo();
                  }
                  return values;
                }
                """)));
    }

    @Test
    void declareIsReserved() {
        var tokens = new Lexer("declare define end as").scan();
        assertEquals(Token.Type.DECLARE, tokens.get(0).type());
        assertEquals(Token.Type.DEFINE, tokens.get(1).type());
        assertEquals(Token.Type.END, tokens.get(2).type());
        assertEquals(Token.Type.AS, tokens.get(3).type());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int declare): void {
                  return;
                }
                """));
    }

    private static void assertNamespaceRejects(String source) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("namespace") || error.getMessage().contains("type-only"),
                () -> "unexpected error: " + error.getMessage());
    }
}

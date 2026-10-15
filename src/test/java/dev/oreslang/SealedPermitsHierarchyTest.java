package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Source-level admission tests for closed nominal class/interface hierarchies. */
final class SealedPermitsHierarchyTest {
    private static Ast.Program checked(String source) {
        return TypeChecker.checkTypes(Parser.parse(source));
    }

    private static void rejects(String source, String fragment) {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> checked(source));
        assertTrue(ex.getMessage().contains(fragment), ex.getMessage());
    }

    @Test
    void explicitPermitsWithFromClassAlias() {
        Ast.Program program = checked("""
                define sealed class WebEvent permits Click, Scroll, KeyPress as
                end
                define final class Click from WebEvent as
                end
                define final class Scroll extends WebEvent as
                end
                define final class KeyPress extends WebEvent as
                end
                """);
        Ast.ClassDecl base = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(base.isSealed());
        assertFalse(base.isFinal());
        assertEquals(java.util.List.of("Click", "Scroll", "KeyPress"), base.permits());
    }

    @Test
    void inferredSameUnitPermitListAndSealedChildChain() {
        assertDoesNotThrow(() -> checked("""
                define sealed class Root as end
                define sealed class Middle extends Root permits Leaf as end
                define final class Leaf extends Middle as end
                """));
    }

    @Test
    void sealedInterfaceMayPermitFinalImplementations() {
        assertDoesNotThrow(() -> checked("""
                define sealed interface Input permits Click, Key as end
                define final class Click implements Input as end
                define final class Key implements Input as end
                """));
    }

    @Test
    void rejectingAnUnauthorizedSubclassCannotFallBackToOrdinaryInheritance() {
        rejects("""
                define sealed class Root permits Good as end
                define final class Good extends Root as end
                define final class Bad extends Root as end
                """, "does not permit");
    }

    @Test
    void rejectingAnUnlistedInterfaceImplementationCannotBypassSealing() {
        rejects("""
                define sealed interface Root permits Good as end
                define final class Good implements Root as end
                define final class Bad implements Root as end
                """, "does not permit");
    }

    @Test
    void everyExplicitPermittedNameMustNameARealDirectSubtype() {
        rejects("""
                define sealed class Root permits Good, Missing as end
                define final class Good extends Root as end
                """, "not a direct subtype");
        rejects("""
                define sealed interface Root permits Good, Unrelated as end
                define final class Good implements Root as end
                define final class Unrelated as end
                """, "not a direct subtype");
    }

    @Test
    void nonclosedDirectChildCannotOpenASealedHierarchy() {
        rejects("""
                define sealed class Root permits Child as end
                define class Child extends Root as end
                """, "must be sealed or final");
        rejects("""
                define sealed interface Root permits Child as end
                define class Child implements Root as end
                """, "must be sealed or final");
    }

    @Test
    void finalMeansNoSubclassMayInheritFromIt() {
        rejects("""
                define final class Base as end
                define class Child extends Base as end
                """, "cannot be extended");
    }

    @Test
    void abstractFinalAndSealedFinalCannotCoexist() {
        rejects("""
                define abstract final class Base as end
                """, "abstract and final");
        rejects("""
                define sealed final class Base as end
                """, "sealed and final");
    }

    @Test
    void sealedHierarchyCannotBeEmpty() {
        rejects("""
                define sealed class Empty as end
                """, "at least one direct subtype");
        rejects("""
                define sealed interface Empty as end
                """, "at least one direct subtype");
    }

    @Test
    void sealedSubtypeMustStayInsideOwningModule() {
        rejects("""
                define module parent_module as
                    pub define sealed class Root permits Child as end
                end
                define module child_module as
                    define final class Child extends Root as end
                end
                """, "same-module");
    }

    @Test
    void permitsIsContextualAndRejectsMalformedHeaders() {
        var tokens = new Lexer("sealed permits final").scan();
        assertEquals(Token.Type.IDENT, tokens.get(0).type());
        assertEquals(Token.Type.IDENT, tokens.get(1).type());
        assertEquals(Token.Type.IDENT, tokens.get(2).type());

        assertDoesNotThrow(() -> checked("""
                fnc permits(int sealed, int final_value): int {
                    return sealed + final_value;
                }
                """));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define sealed class Root permits Child, Child as end
                        """));
        assertTrue(duplicate.getMessage().contains("duplicate permitted subtype"));

        IllegalArgumentException unsealed = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Root permits Child as end
                        """));
        assertTrue(unsealed.getMessage().contains("requires a sealed class"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define sealed class Root permits as end
                        """));
        assertTrue(missing.getMessage().contains("expected direct subtype name"));
    }
}

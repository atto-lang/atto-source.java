package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-three admission: empty-body actors reuse the existing scheduler
 * representation; a named standalone binding stays source/AST visible but
 * cannot become a runtime ActorRef until safe lifecycle lowering exists.
 */
final class StandaloneActorDeclarationAdmissionTest {
    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    private static Ast.ClassDecl actor(String source, String name) {
        for (Ast.ModuleDecl module : Parser.parse(source).modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass && klass.name().equals(name)) {
                    return klass;
                }
            }
        }
        throw new AssertionError("actor not found: " + name);
    }

    @Test
    void compactBodylessActorTypeIsAnExecutableEmptyActorClass() {
        var klass = actor("""
                define module carts as
                  pub define actor Cart;
                end
                """, "Cart");
        assertEquals(Ast.Visibility.PUBLIC, klass.visibility());
        assertTrue(klass.actorKind() != Ast.ActorKind.NONE);
        assertTrue(klass.methods().isEmpty());
        assertTrue(klass.fields().isEmpty());
        assertNull(klass.standaloneBinding());
        assertEquals(1, klass.actorConcurrency().requestConcurrency());
        assertEquals(1, klass.actorConcurrency().messageConcurrency());
        assertDoesNotThrow(() -> check("""
                define module carts as
                  pub define actor Cart;
                end
                """));
    }

    @Test
    void compactActorSatisfiesPublicModuleActorRequirement() {
        assertDoesNotThrow(() -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module carts conforms CartApi as
                  pub define actor Cart;
                end
                """));
    }

    @Test
    void compactActorIsNominallyEquivalentToEmptyBlockDeclaration() {
        Ast.ClassDecl compact = actor("pub define actor Cart;", "Cart");
        Ast.ClassDecl block = actor("""
                pub define actor Cart as
                end
                """, "Cart");
        assertEquals(block, compact,
                "both syntaxes must lower to identical actor metadata");
    }

    @Test
    void compactPrivateActorCannotSatisfyPublicModuleRequirement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module carts conforms CartApi as
                  define actor Cart;
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void actorBindingSyntaxIsPreservedButRejectedUntilRuntimeLowering() {
        var parsed = actor("""
                define module carts as
                  pub define actor Cart as cart;
                end
                """, "Cart");
        assertEquals("cart", parsed.standaloneBinding());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define module carts as
                  pub define actor Cart as cart;
                end
                """));
        assertTrue(error.getMessage().contains("requires compiler-checked actor activation"));
        assertTrue(error.getMessage().contains("not executable yet"));
    }

    @Test
    void bindingNameCannotAliasTheActorTypeName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("define actor Cart as Cart;"));
        assertTrue(error.getMessage().contains("distinct, nonblank actor binding"));
    }

    @Test
    void genericStandaloneBindingFailsClosedRatherThanErasingArguments() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("define actor Cart<T> as cart;"));
        assertTrue(error.getMessage().contains("does not yet support generics"));
    }

    @Test
    void standaloneBindingIsNotMadeCallableByAReceiverContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  pub mtd count(Cart cart)(): int;
                end
                define module carts conforms CartApi as
                  pub define actor Cart as cart;
                end
                """));
        assertTrue(error.getMessage().contains("not executable yet"));
    }

    @Test
    void standaloneBindingCannotBeUsedAsAnImplicitActorHandle() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define module carts as
                  pub define actor Cart as cart;
                  pub fnc get(): Cart { return cart; }
                end
                """));
        assertTrue(error.getMessage().contains("not executable yet"));
    }

    @Test
    void privateStandaloneBindingIsAlsoRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> check("define actor Cart as cart;"));
        assertTrue(error.getMessage().contains("not executable yet"));
    }

    @Test
    void exportedInterfaceCannotCertifyUnloweredStandaloneBinding() {
        var parsed = Parser.parse("""
                define module carts as
                  pub define actor Cart as cart;
                end
                """);
        var header = new ExportInterfaceCodec.Header(
                "", "test-compiler", "test-abi", "safe");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExportInterfaceCollector.collect(parsed, header));
        assertTrue(error.getMessage().contains("cannot be exported"));
    }

    @Test
    void incrementalBuildMustNotEmitAnAbiForUnloweredBinding() {
        var compiler = new IncrementalCompiler();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                compiler.compile(Map.of("cart.atto", """
                        define module carts as
                          pub define actor Cart as cart;
                        end
                        """)));
        assertTrue(error.getMessage().contains("not executable yet")
                || error.getMessage().contains("no checked activation/dispatch ABI"));
    }

    @Test
    void duplicateCompactAndBlockActorNamesAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define module carts as
                  pub define actor Cart;
                  pub define actor Cart as
                  end
                end
                """));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void compactActorDoesNotCreateASpuriousBinding() {
        var klass = actor("pub define actor Cart;", "Cart");
        assertNull(klass.standaloneBinding());
        assertFalse(klass.actorKind() == Ast.ActorKind.NONE);
    }
}

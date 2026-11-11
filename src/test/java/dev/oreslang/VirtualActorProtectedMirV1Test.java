package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.VirtualActorProtectedMirV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static MIR contract verification only; no backend/guest execution is
 * claimed. A checked plan deliberately remains unlowered in TypeChecker.
 */
final class VirtualActorProtectedMirV1Test {
    private static final String CART = """
            define virtual actor Cart as
              state { let int quantity: 0; }
              add(int n) -> void ::
                handle_state(critical-write |s| -> {
                  s.quantity = s.quantity + n;
                });
              end
            end
            """;

    private static Ast.VirtualActorDecl actor(String source) {
        Ast.Decl decl = Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        return assertInstanceOf(Ast.VirtualActorDecl.class, decl);
    }

    private static void rejected(String source, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(actor(source)));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_V1_REJECTED"),
                error::getMessage);
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    @Test
    void cartBuildsTypedImmutablePreloweringContract() {
        var mir = VirtualActorProtectedMirV1.analyze(actor(CART));
        assertEquals("Cart", mir.declaration());
        assertEquals(Ast.Visibility.PRIVATE, mir.visibility());
        assertEquals("quantity", mir.state().name());
        assertEquals("int", mir.state().type().name());
        assertEquals(0L, mir.state().initialValue());
        assertEquals(1, mir.methods().size());
        var method = mir.referenceType().method("add", 1);
        assertEquals("VirtualActorRef[Cart]", mir.referenceType().spelling());
        assertEquals("n", method.parameter());
        assertEquals("quantity", method.field());
        assertEquals("int", method.parameterType().name());
        assertEquals("void", method.returnType().name());
        assertThrows(IllegalArgumentException.class,
                () -> mir.referenceType().method("add", 2));
        assertThrows(UnsupportedOperationException.class,
                () -> mir.methods().clear(), "the plan is not a writable runtime authority");
    }

    @Test
    void typeCheckerStillDeniesExecutableVirtualActorAfterSuccessfulMirAnalysis() {
        var parsed = Parser.parse(CART);
        assertNotNull(VirtualActorProtectedMirV1.analyze(actor(CART)));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(parsed));
        assertTrue(failure.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }

    @Test
    void rejectsWrongScalarStateAndNonconstantInitializer() {
        rejected(CART.replace("let int quantity", "let string quantity"),
                "checked i64");
        rejected(CART.replace("quantity: 0", "quantity: n"),
                "constant i64");
        rejected(CART.replace("state { let int quantity: 0; }",
                "state { let int quantity: 0; let int other: 0; }"),
                "exactly one");
    }

    @Test
    void rejectsUnfencedReadsAndWritesOrEscapingAliases() {
        rejected(CART.replace("critical-write", "critical-read"),
                "token/provenance mismatch");
        rejected(CART.replace("s.quantity = s.quantity + n;", "return s;"),
                "unrecognized protected method body");
        rejected(CART.replace("s.quantity = s.quantity + n;",
                "s.quantity = s.quantity + n; s.quantity = 0;"),
                "unrecognized protected method body");
    }

    @Test
    void rejectsUnsupportedTypeAndShadowedStateLoan() {
        rejected(CART.replace("add(int n)", "add(string n)"),
                "checked i64");
        rejected(CART.replace("add(int n)", "add(int s)")
                        .replace(" + n;", " + s;"),
                "shadows");
        rejected(CART.replace("add(int n)", "add(int quantity)")
                        .replace(" + n;", " + quantity;"),
                "shadows");
        rejected(CART.replace("-> void ::", "-> int ::"),
                "return void");
    }

    @Test
    void protectedMethodCannotSmuggleAnUnverifiedOrForgedTokenKind() {
        var source = actor(CART);
        var original = source.methods().getFirst();
        var unverified = new Ast.ProtectedMethod(original.name(),
                original.parameters(), original.returnType(), original.bodyTokens());
        var forgedActor = new Ast.VirtualActorDecl(source.name(),
                source.visibility(), source.stateFields(), List.of(unverified));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(forgedActor));

        var forgedTypes = new ArrayList<>(original.bodyTokenKinds());
        forgedTypes.set(17, "UNVERIFIED");
        var forged = new Ast.ProtectedMethod(original.name(), original.parameters(),
                original.returnType(), original.bodyTokens(), forgedTypes);
        var poisoned = new Ast.VirtualActorDecl(source.name(),
                source.visibility(), source.stateFields(), List.of(forged));
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(poisoned));
    }

    @Test
    void impossibleMethodShapeDoesNotBecomeExecutable() {
        var noMethods = actor("""
                define virtual actor Cart as
                  state { let int quantity: 0; }
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> VirtualActorProtectedMirV1.analyze(noMethods));
        var ordinaryGate = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        define virtual actor Cart as
                          state { let int quantity: 0; }
                        end
                        """)));
        assertTrue(ordinaryGate.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
    }

    @Test
    void rejectUnsafeMethodThroughProductionTypeChecker() {
        var source = CART.replace("s.quantity = s.quantity + n;", "return s;");
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_UNSUPPORTED"));
        assertTrue(error.getMessage().contains("VIRTUAL_ACTOR_MIR_V1_REJECTED"));
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** External actor methods remain non-executable until actor-context lowering. */
final class ExternalActorReceiverAstAdmissionTest {
    private static Ast.Decl declaration(String source) {
        return Parser.parse(source).modules().getFirst().declarations().getFirst();
    }

    @Test
    void externalMtdHasItsOwnNominalReceiverAndValueParameters() {
        Ast.ExternalActorMethodDecl method = assertInstanceOf(
                Ast.ExternalActorMethodDecl.class,
                declaration("define pub mtd update(Worker worker)(int delta): void { return; }"));
        assertEquals("Worker", method.actorName());
        assertEquals("worker", method.receiverName());
        assertEquals("update", method.name());
        assertEquals(1, method.parameters().size());
        assertEquals(Ast.Visibility.PUBLIC, method.visibility());
    }

    @Test
    void freeFunctionTakingAnActorRemainsJustAFunction() {
        assertInstanceOf(Ast.FunctionDecl.class,
                declaration("define pub fnc update(Worker worker): void { return; }"));
    }

    @Test
    void definePrefixedOrdinaryCallableRemainsNonActorFunction() {
        assertInstanceOf(Ast.FunctionDecl.class,
                declaration("define pub fnc inspect(Worker worker): void { return; }"));
        assertInstanceOf(Ast.FunctionDecl.class,
                declaration("define pub routine inspect(Worker worker): void { return; }"));
        assertInstanceOf(Ast.FunctionDecl.class,
                declaration("define module workers as\n"
                        + "  define pub fnc inspect(Worker worker): void { return; }\n"
                        + "end\n"));
    }

    @Test
    void definePrefixedExternalFunctionNeverGainsActorIngress() {
        for (String spelling : new String[]{
                "define external fnc inspect(): void { return; }",
                "define external routine inspect(): void { return; }"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(spelling));
            assertTrue(error.getMessage().contains(
                    "'external' is allowed only for nominal actor mtd declarations"),
                    error.getMessage());
        }
    }

    @Test
    void moduleScopedExternalMtdPreservesItsDeclarationKind() {
        assertInstanceOf(Ast.ExternalActorMethodDecl.class,
                declaration("define module workers as\n"
                        + "  define external mtd receive(Worker worker)(int value): void { return; }\n"
                        + "end\n"));
    }

    @Test
    void externalReceiverIsNotExecutableWithoutActorPermitLowering() {
        Ast.Program parsed = Parser.parse(
                "define module workers as\n"
                        + "  pub define actor Worker as end\n"
                        + "  define mtd update(Worker worker)(int delta): void { return; }\n"
                        + "end\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(error.getMessage().contains("EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void structuralOrGenericReceiverIsNotAnActorContext() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(
                        "define mtd update(Worker<int> worker)(): void { return; }"));
        assertTrue(error.getMessage().contains("non-generic nominal actor"), error.getMessage());
    }

    @Test
    void receiverBindingCannotBeShadowedByValueParameter() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(
                        "define mtd update(Worker worker)(int worker): void { return; }"));
        assertTrue(error.getMessage().contains("shadow the receiver binding"), error.getMessage());
    }

    @Test
    void userDefinedReceiverCannotClaimSupervisorPanicHook() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(
                        "define mtd on_panic(Worker worker)(): void { return; }"));
        assertTrue(error.getMessage().contains("compiler-owned or CONTROL hooks"), error.getMessage());
    }
}

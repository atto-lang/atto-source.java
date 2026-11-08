package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opaque protected-state method tokens are not compiler-verified executable
 * effects. A capability visitor must fail closed instead of accepting them
 * as an empty body.
 */
final class UntrustedOpaqueDeclarationAdmissionTest {
    private static Ast.Program program(Ast.Decl declaration) {
        return new Ast.Program(List.of(new Ast.ModuleDecl(
                "app", List.of(declaration))));
    }

    private static void denied(Ast.Decl declaration, String kind) {
        SecurityException error = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(
                        program(declaration), IsolatePolicy.developer()));
        assertTrue(error.getMessage().contains(kind), error::getMessage);
    }

    @Test
    void protectedStateContainerCannotGainUncheckedCapabilityAdmission() {
        denied(new Ast.StateContainerDecl(
                "Secret", Ast.Visibility.PRIVATE, List.of(), List.of()),
                "StateContainerDecl");
    }

    @Test
    void virtualActorCannotGainUncheckedCapabilityAdmission() {
        denied(new Ast.VirtualActorDecl(
                "SecretActor", Ast.Visibility.PRIVATE, List.of(), List.of()),
                "VirtualActorDecl");
    }
}

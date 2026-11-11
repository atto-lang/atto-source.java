package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security regression: the optimizer must never downgrade a transport actor
 * to the compatibility constructor's GENERIC 1x1 policy.
 * This is compiler/AST evidence, not executable HTTP guest evidence.
 */
final class TreeShakerHttpActorPolicyTest {
    private static Ast.ClassDecl keptPolicy(Ast.ActorConcurrency policy) {
        Ast.ClassDecl original = new Ast.ClassDecl(
                "Endpoint", Ast.Visibility.PUBLIC, false, Ast.ActorKind.SHARED,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), policy);
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("service", List.of(original))));
        TreeShaker.Result optimized = TreeShaker.shake(program,
                BuildOptions.library(Map.of()));
        assertTrue(optimized.retained("service.Endpoint"),
                "public HTTP actor was pruned unexpectedly");
        Ast.ClassDecl kept = (Ast.ClassDecl) optimized.program().modules()
                .getFirst().declarations().getFirst();
        assertEquals(original, kept,
                "tree-shaking must preserve the actor transport and immutable quotas");
        assertTrue(kept.fields().isEmpty(),
                "declaration quotas must never turn into writable actor state fields");
        return kept;
    }

    @Test
    void serialHttpActorRetainsHttpTransportRatherThanGenericMailbox() {
        Ast.ActorConcurrency serial = Ast.ActorConcurrency.serial(Ast.ActorTransport.HTTP);
        Ast.ClassDecl result = keptPolicy(serial);
        assertEquals(Ast.ActorTransport.HTTP, result.actorConcurrency().transport());
        assertEquals(1, result.actorConcurrency().requestConcurrency());
        assertEquals(1, result.actorConcurrency().messageConcurrency());
    }

    @Test
    void unverifiedThreeByFourCannotBeLaunderedIntoPermittedSerialPolicy() {
        Ast.ActorConcurrency pending = new Ast.ActorConcurrency(
                Ast.ActorTransport.HTTP, 3, 4);
        Ast.ClassDecl result = keptPolicy(pending);
        assertEquals(pending, result.actorConcurrency(),
                "optimizing before typechecking must not mask an unsafe policy");
    }

    @Test
    void compiledSerialHttpSourceRetainsTransportAfterTypecheckingAndOptimization() {
        TreeShaker.Result result = dev.oreslang.compiler.OresCompiler.compileForBuild("""
                define pub serial http actor Endpoint as
                    receive(ActorMail<int> mail): void { return; }
                end
                """, BuildOptions.library(Map.of()));
        assertTrue(result.retained("root.Endpoint") ||
                result.retained("service.Endpoint") ||
                result.program().modules().stream()
                    .flatMap(module -> module.declarations().stream())
                    .anyMatch(Ast.ClassDecl.class::isInstance),
                "the public actor must remain in the optimized module");
        Ast.ClassDecl kept = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(klass -> klass.name().equals("Endpoint"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorConcurrency.serial(Ast.ActorTransport.HTTP),
                kept.actorConcurrency(),
                "whole compiler build must not launder serial HTTP into generic mail");
    }

    @Test
    void invalidThreeByFourSourceCannotReachOptimizedLibraryArtifact() {
        String source = """
                define pub http actor Endpoint as
                    int requestConcurrency = 3;
                    int messageConcurrency = 4;
                end
                """;
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.compileForBuild(
                        source, BuildOptions.library(Map.of())));
        assertTrue(denied.getMessage().contains("Refusing unsafe configuration"),
                denied::getMessage);
    }

    @Test
    void nonHttpTransportDiscriminantsArePreserved() {
        for (Ast.ActorTransport transport : List.of(
                Ast.ActorTransport.TCP, Ast.ActorTransport.WEBSOCKET,
                Ast.ActorTransport.GENERIC)) {
            assertEquals(transport, keptPolicy(
                    Ast.ActorConcurrency.serial(transport))
                    .actorConcurrency().transport());
        }
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security regression: the optimizer must never downgrade a transport actor
 * to the compatibility constructor's GENERIC 1x1 policy.
 * This is compiler/AST evidence, not executable HTTP guest evidence.
 */
final class TreeShakerHttpActorPolicyTest {
    private static String fixture(String filename) {
        try (var stream = TreeShakerHttpActorPolicyTest.class.getResourceAsStream(
                "/http-actor-v1/" + filename)) {
            if (stream == null) throw new IllegalStateException("missing canonical .atto fixture: " + filename);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException readFailure) {
            throw new UncheckedIOException(readFailure);
        }
    }

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
    void canonicalSerialAndExplicitHttpFixturesRetainDistinctReceivePolicies() {
        // Both fixtures have HTTP transport and numeric 1x1 capacity, but
        // only the serial declaration holds a live receive permit across
        // suspension. The optimizer must NEVER invent or erase this bit.
        // Compiler-backed admission only; no HTTP socket execution claim.
        Ast.ActorConcurrency serial = null;
        Ast.ActorConcurrency explicit = null;
        for (String fixture : List.of("serial-1x1.atto", "explicit-1x1.atto")) {
            TreeShaker.Result result = dev.oreslang.compiler.OresCompiler.compileForBuild(
                    fixture(fixture), BuildOptions.library(Map.of()));
            assertTrue(result.retained(dev.oreslang.parser.Parser.ROOT_MODULE + ".Endpoint"),
                    "public canonical Endpoint must not be optimized away");
            Ast.ClassDecl kept = result.program().modules().stream()
                    .flatMap(module -> module.declarations().stream())
                    .filter(Ast.ClassDecl.class::isInstance)
                    .map(Ast.ClassDecl.class::cast)
                    .filter(klass -> klass.name().equals("Endpoint"))
                    .findFirst().orElseThrow();
            Ast.ActorConcurrency policy = kept.actorConcurrency();
            assertEquals(Ast.ActorTransport.HTTP, policy.transport(),
                    "whole compiler build must not launder HTTP transport into generic mail");
            assertEquals(1, policy.requestConcurrency());
            assertEquals(1, policy.messageConcurrency());
            if (fixture.startsWith("serial-")) {
                assertTrue(policy.serialReceive(), "serial receive-only policy was erased");
                serial = policy;
            } else {
                assertFalse(policy.serialReceive(),
                        "explicit numeric 1x1 must never silently become serial");
                explicit = policy;
            }
        }
        assertNotNull(serial);
        assertNotNull(explicit);
        assertNotEquals(serial, explicit,
                "serial receive ownership is semantic, not numeric capacity");
    }

    @Test
    void canonicalUnsupportedThreeByFourFixtureNeverReachesOptimizedOutput() {
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.compileForBuild(
                        fixture("unsupported-3x4.atto"), BuildOptions.library(Map.of())));
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

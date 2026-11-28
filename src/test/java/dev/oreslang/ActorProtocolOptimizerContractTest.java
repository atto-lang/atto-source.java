package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A compiler IR transformation must not erase actor DATA contracts or sealed
 * state permissions. This directly exercises the optimizer; not guest AOT.
 */
final class ActorProtocolOptimizerContractTest {
    @Test
    void sourceParserAndAnnotationExpansionKeepReceivesAfterMainlinePort() {
        String source = """
                define actor Worker receives String as
                  receive(ActorMail<String> mail): void { return; }
                end
                """;
        Ast.ClassDecl parsed = (Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        assertEquals("String", parsed.actorMessageContract().receives().name());
        Ast.ClassDecl expanded = (Ast.ClassDecl) dev.oreslang.ast.AnnotationExpander
                .expand(Parser.parse(source)).modules().getFirst().declarations().getFirst();
        assertEquals(parsed.actorMessageContract(), expanded.actorMessageContract());
    }

    @Test
    void treeShakerPreservesActorAuthorityMetadataAndItsTypeRoots() {
        Ast.ClassDecl inbound = new Ast.ClassDecl(
                "Payload", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        Ast.ClassDecl outbound = new Ast.ClassDecl(
                "Outbound", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        Ast.ClassDecl stateType = new Ast.ClassDecl(
                "Stored", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        Ast.ClassDecl annotationType = new Ast.ClassDecl(
                "Marker", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of());
        Ast.ActorMessageContract contract = new Ast.ActorMessageContract(
                Ast.TypeRef.simple("Payload"), Ast.TypeRef.simple("Outbound"));
        Ast.ClassDecl actor = new Ast.ClassDecl(
                "Worker", Ast.Visibility.PUBLIC, false, Ast.ActorKind.SHARED,
                List.of(), List.of(), List.of(), List.of(), null, List.of(),
                Ast.ActorConcurrency.serial(Ast.ActorTransport.GENERIC),
                List.of(new Ast.ProtectedStateField("cache",
                        Ast.TypeRef.simple("Stored"), new Ast.LiteralExpr(0))),
                List.of(new Ast.Annotation("metadata",
                        List.of(Ast.TypeRef.simple("Marker")))),
                contract);
        TreeShaker.Result shaken = TreeShaker.shake(
                new Ast.Program(List.of(new Ast.ModuleDecl(
                        "service", List.of(inbound, outbound, stateType, annotationType, actor)))),
                BuildOptions.library(Map.of()));
        for (String type : List.of("Worker", "Payload", "Outbound", "Stored", "Marker")) {
            assertTrue(shaken.retained("service." + type),
                    "actor authority/dependency was lost during pruning: " + type);
        }
        Ast.ClassDecl preserved = shaken.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(klass -> klass.name().equals("Worker"))
                .findFirst().orElseThrow();
        assertEquals(contract, preserved.actorMessageContract());
        assertEquals(actor.sealedStateFields(), preserved.sealedStateFields());
        assertEquals(actor.annotations(), preserved.annotations());
        assertEquals(actor.actorConcurrency(), preserved.actorConcurrency());
    }

    @Test
    void ordinaryClassesNeverGainActorMessageAuthority() {
        assertThrows(IllegalArgumentException.class, () -> new Ast.ClassDecl(
                "Impostor", Ast.Visibility.PUBLIC, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), null, List.of(),
                Ast.ActorConcurrency.serial(Ast.ActorTransport.GENERIC),
                List.of(), List.of(),
                new Ast.ActorMessageContract(Ast.TypeRef.simple("Payload"), null)));
    }
}

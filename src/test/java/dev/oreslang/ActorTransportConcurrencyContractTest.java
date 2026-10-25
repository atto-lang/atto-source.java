package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorTransportConcurrencyContractTest {
    private static Ast.ClassDecl actor(String declaration) {
        Ast.Program parsed = Parser.parse(declaration);
        return (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
    }

    @Test void serialAliasAndExplicitOneOneHaveIdenticalImmutablePolicy() {
        Ast.ClassDecl alias = actor("""
                define serial http actor CriticalSectionActor as
                end
                """);
        Ast.ClassDecl explicit = actor("""
                define http actor CriticalSectionActor as
                  int requestConcurrency = 1;
                  int messageConcurrency = 1;
                end
                """);
        assertEquals(Ast.ActorKind.SHARED, alias.actorKind());
        assertEquals(Ast.ActorTransport.HTTP, alias.actorConcurrency().transport());
        assertEquals(new Ast.ActorConcurrency(Ast.ActorTransport.HTTP, 1, 1),
                explicit.actorConcurrency());
        assertEquals(alias.actorConcurrency(), explicit.actorConcurrency());
        assertEquals(alias, explicit, "serial is a canonical AST alias for explicit 1/1");
        // Configuration is not actor-owned mutable state.
        assertTrue(alias.fields().isEmpty());
        assertTrue(explicit.fields().isEmpty());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define serial http actor CriticalSectionActor as
                  receive(ActorMail<int> mail): void { return; }
                end
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define http actor CriticalSectionActor as
                  int requestConcurrency = 1;
                  int messageConcurrency = 1;
                  receive(ActorMail<int> mail): void { return; }
                end
                """)));
    }

    @Test void annotationExpansionPreservesTransportPolicyBeforeSafetyChecking() {
        String source = """
                define http actor ConcurrentEndpoint as
                  int requestConcurrency = 3;
                  int messageConcurrency = 4;
                end
                """;
        Ast.Program parsed = Parser.parse(source);
        Ast.Program expanded = AnnotationExpander.expand(parsed);
        Ast.ClassDecl before = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.ClassDecl after = (Ast.ClassDecl) expanded.modules().getFirst().declarations().getFirst();
        assertEquals(before.actorConcurrency(), after.actorConcurrency(),
                "AST transforms must not silently fall back to generic serial actor policy");
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
    }

    @Test void tcpAndWebsocketConfigurationsAreDistinctAndLocal() {
        assertEquals(Ast.ActorTransport.TCP,
                actor("define tcp actor Socket as end").actorConcurrency().transport());
        assertEquals(Ast.ActorTransport.WEBSOCKET,
                actor("define websocket actor Socket as end").actorConcurrency().transport());
        assertEquals(Ast.ActorTransport.WEBSOCKET,
                actor("define ws actor Socket as end").actorConcurrency().transport());
        assertEquals(Ast.ActorTransport.GENERIC,
                actor("define actor Socket as end").actorConcurrency().transport());
    }

    @Test void parallelConfigurationParsesButCannotEscapeSingleWriterRuntimeGate() {
        String source = """
                define http actor HighThroughput as
                  int requestConcurrency = 3;
                  int messageConcurrency = 4;
                  receive(ActorMail<String> mail): void { return; }
                end
                """;
        Ast.ActorConcurrency config = actor(source).actorConcurrency();
        assertEquals(3, config.requestConcurrency());
        assertEquals(4, config.messageConcurrency());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(denied.getMessage().contains("Refusing unsafe configuration"),
                denied::getMessage);
    }

    @Test void transportActorsCannotBeSpawnedThroughGenericMailboxDispatch() {
        String source = """
                define serial http actor HttpHandler as
                  receive(ActorMail<String> mail): void { return; }
                end
                pub routine main(): void {
                  val server = spawn HttpHandler();
                  return;
                }
                """;
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(denied.getMessage().contains("request-dispatch adapter"),
                denied::getMessage);
    }

    @Test void invalidConfigurationsAndKeywordMisuseAreRejectedEarly() {
        String[] invalid = {
            "define serial http actor Server as int requestConcurrency = 2; end",
            "define http actor Server as int requestConcurrency = 0; end",
            "define http actor Server as int messageConcurrency = 0; end",
            "define http actor Server as int requestConcurrency = 1; int requestConcurrency = 1; end",
            "define http actor Server as int messageConcurrency = 1; int messageConcurrency = 1; end",
            "define http actor Server as int requestConcurrency = 1 + 1; end",
            "define http actor Server as int messageConcurrency = -1; end",
            "define http actor Server as int requestConcurrency = 2147483648; end",
            "define http tcp actor Server as end",
            "define serial serial http actor Server as end",
            "define serial class Server as end",
            "define actor Server as int requestConcurrency = 1; end"
        };
        for (String invalidSource : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(invalidSource),
                    invalidSource);
        }
    }

    @Test void nonActorNamesRemainValidAndActorConcurrenciesDoNotLeakToFields() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                    val int serial = 1;
                    val int http = 2;
                    val int tcp = 3;
                    return;
                }
                """));
    }

    @Test void nestedActorConfigurationCannotWidenInheritedPolicy() {
        String source = """
                define http actor Base as
                  receive(ActorMail<int> mail): void { return; }
                end
                define websocket actor Child extends Base as
                  receive(ActorMail<int> mail): void { return; }
                end
                """;
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(denied.getMessage().contains("transport and concurrency policy"),
                denied::getMessage);
    }

    @Test void actorMetadataIsAvailableWithinModules() {
        Ast.Program parsed = Parser.parse("""
                define module sample as
                  define serial http actor Endpoint as
                    int requestConcurrency = 1;
                    int messageConcurrency = 1;
                  end
                end
                """);
        Ast.ClassDecl endpoint = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorConcurrency.serial(Ast.ActorTransport.HTTP), endpoint.actorConcurrency());
    }
}

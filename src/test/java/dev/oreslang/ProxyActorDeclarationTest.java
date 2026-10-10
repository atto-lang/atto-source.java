package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ProxyActorDeclarationTest {

    private static final String DECLARATION = """
            define contract RemoteTransformer as
                fnc transform(int value) => int;
            end

            define proxy actor TransformProxy
                conforms to RemoteTransformer
                discover "workers.transform.v1"
                transport tcp
            as
            end
            """;

    @Test
    void parsesSourceProxyWithTypedContractDiscoveryAndTransport() {
        Ast.Program program = Parser.parse(DECLARATION);
        Ast.ClassDecl proxy = program.modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
        assertEquals("TransformProxy", proxy.name());
        assertEquals(Ast.ActorKind.SHARED, proxy.actorKind());
        assertNotNull(proxy.proxySpec());
        assertEquals("RemoteTransformer", proxy.proxySpec().remoteContract().name());
        assertEquals("workers.transform.v1", proxy.proxySpec().serviceKey());
        assertEquals("tcp", proxy.proxySpec().transportPolicy());
        assertTrue(proxy.methods().isEmpty(), "local proxy must not fabricate remote methods");
    }

    @Test
    void spawningUnimplementedRemoteProxyFailsClosed() {
        Ast.Program program = Parser.parse(DECLARATION + """
                pub routine main(): void {
                    const proxy = spawn TransformProxy();
                    return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains("remote spawn is not executable yet"),
                failure::getMessage);
    }

    @Test
    void rejectsMissingOrInvalidTransportAndImperativeProxyBody() {
        for (String invalid : new String[] {
                DECLARATION.replace("transport tcp", "transport insecure-tcp"),
                DECLARATION.replace("transport tcp", "transport"),
                DECLARATION.replace("as\nend", "as\nforward(): void { return; }\nend"),
                DECLARATION.replace("discover \"workers.transform.v1\"", "discover \"\"")
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(invalid), invalid);
        }
    }

    @Test
    void contextualProxyWordRemainsAnOrdinaryIdentifier() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc echo(int proxy): int { return proxy; }
                pub routine main(): void { return; }
                """)));
    }
}

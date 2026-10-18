package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class DynInterfaceViewTest {
    private static final String PRELUDE = """
            define interface Deployable as
                fnc deploy(): int;
            end
            define class Database implements Deployable as
                pub deploy(): int { return 40; }
            end
            define class WebServer implements Deployable as
                pub deploy(): int { return 2; }
            end
            """;

    @Test
    void dynMarkerSurvivesParsingAsAnExplicitTypeRef() {
        Ast.Program parsed = Parser.parse(PRELUDE + """
                fnc deploy(dyn Deployable resource): int {
                    return resource.deploy();
                }
                """);
        Ast.FunctionDecl fn = parsed.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        Ast.TypeRef type = fn.parameters().getFirst().type();
        assertTrue(type.isDyn());
        assertEquals("Deployable", type.dynTarget().name());
    }

    @Test
    void checkedDynViewsAllowHeterogeneousImplementationsAndDispatch() throws Exception {
        String program = PRELUDE + """
                fnc deploy(dyn Deployable resource): int {
                    return resource.deploy();
                }
                pub routine main(): void {
                    val dyn Deployable db = new Database();
                    val dyn Deployable web = new WebServer();
                    stdio.stdout.write(deploy(db));
                    stdio.stdout.write(deploy(web));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "dyn-dispatch.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("402", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nestedCollectionAndReturnTypesCanCarryDynViews() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PRELUDE + """
                fnc passthrough(dyn Deployable resource): dyn Deployable {
                    return resource;
                }
                fnc batch(List<dyn Deployable> resources): void { return; }
                """)));
    }

    @Test
    void dynNeverConvertsAnUnrelatedClassIntoAnInterface() {
        String source = PRELUDE + """
                define class Unrelated as end
                fnc bad(): void {
                    val dyn Deployable resource = new Unrelated();
                    return;
                }
                """;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("expected"), failure::getMessage);
    }

    @Test
    void dynRejectsNonInterfacesAndUnresolvedTargets() {
        for (String target : new String[] {"int", "Database", "Missing", "List<int>", "{count: int}"}) {
            String source = PRELUDE + "fnc bad(dyn " + target + " value): void { return; }";
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), target);
            assertTrue(failure.getMessage().contains("dyn requires"), failure::getMessage);
        }
    }

    @Test
    void dynRejectsFieldAndGenericMethodRequirements() {
        for (String iface : new String[] {
                "define interface Bad as val count: int; end",
                "define interface Bad as fnc pick<T>(T input) => int; end"
        }) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(iface + """
                            fnc consume(dyn Bad value): void { return; }
                            """)));
            assertTrue(failure.getMessage().contains("dyn interface"), failure::getMessage);
        }
    }

    @Test
    void dynRejectsReceiverDependentSignaturesAndNestedErasure() {
        for (String source : new String[] {
                "define interface Bad as fnc clone(): self; end fnc use(dyn Bad value): void { return; }",
                PRELUDE + "fnc use(dyn dyn Deployable value): void { return; }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
        }
    }

    @Test
    void dynCannotBeSmuggledThroughActorOrChannelTypeArguments() {
        for (String container : new String[] {
                "ActorRef<dyn Deployable>",
                "ActorMail<dyn Deployable>",
                "Channel<List<dyn Deployable>>",
                "SharedMutex<dyn Deployable>"
        }) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(PRELUDE
                            + "fnc bad(" + container + " value): void { return; }")),
                    container);
            assertTrue(failure.getMessage().contains("transport/ownership ABI"), failure::getMessage);
        }
    }

    @Test
    void erasedTypeAliasCannotBypassTransportRejection() {
        String source = PRELUDE + """
                define type Erased = dyn Deployable;
                define type Indirect = Erased;
                fnc invalid(Channel<Indirect> c): void { return; }
                """;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("transport/ownership ABI"), failure::getMessage);
    }

    @Test
    void dynCannotBeConstructedOrSpawned() {
        for (String code : new String[] {
                "new dyn Deployable()",
                "spawn dyn Deployable()"
        }) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(PRELUDE + "fnc bad(): void { val x = " + code + "; return; }"));
            assertTrue(failure.getMessage().contains("dyn interfaces cannot"), failure::getMessage);
        }
    }
}

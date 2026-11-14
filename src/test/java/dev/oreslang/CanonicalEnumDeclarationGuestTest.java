package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Java/Graal guest source test for canonical enum declarations.
 * The entire match-alias / typed actor receive contract remains separate.
 * Canonical implicit tags are for the local sealed enum layout, NOT a stable
 * network codec or actor transport ABI.
 */
final class CanonicalEnumDeclarationGuestTest {
    private static final String DECLARATION = """
            define enum Messages {
                Reset { reset: int },
                Delta { delta: int },
                Ready
            }
            """;

    @Test
    void parsesCanonicalCasesAndDeterministicLocalTags() {
        Ast.Program program = Parser.parse(DECLARATION);
        Ast.EnumDecl declaration = (Ast.EnumDecl)
                program.modules().getFirst().declarations().getFirst();
        assertEquals("Messages", declaration.name());
        assertEquals(3, declaration.variants().size());
        assertEquals(1L, declaration.variants().get(0).tag());
        assertEquals(2L, declaration.variants().get(1).tag());
        assertEquals(3L, declaration.variants().get(2).tag());
        assertEquals(java.util.Set.of("reset"),
                declaration.variants().get(0).payload().recordMembers().keySet());
        assertNull(declaration.variants().get(2).payload());
    }

    @Test
    void canonicalDeclarationFeedsExistingNominalGuestConstructorAndMatch() throws Exception {
        String source = DECLARATION + """
                fnc decode(Messages message): int {
                    return match message over {
                        on Messages.Reset as (payload) { return payload.reset; }
                        on Messages.Delta as (payload) { return payload.delta; }
                        on Messages.Ready as () { return 0; }
                    };
                }
                pub fnc main(): void {
                    stdio.println(decode(Messages.Reset(infer struct { reset: 41 })));
                    stdio.println(decode(Messages.Delta(infer struct { delta: 5 })));
                    stdio.println(decode(Messages.Ready()));
                    return;
                }
                """;
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "canonical-enum.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("41\n5\n0",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void canonicalEnumSyntaxRejectsMalformedDeclarationsBeforeLowering() {
        for (String invalid : java.util.List.of(
                "define enum M { Run, Run }",
                "define enum M { Run Ready }",
                "define enum M { Run { id: int, id: int } }",
                "define enum M { Run = 44 }",
                "define enum M { Run,",
                "define enum M {}")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(invalid), invalid);
        }
    }

    @Test
    void canonicalEnumsDoNotBypassExistingPayloadSafetyGate() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                "define enum Unsafe { Borrowed { children: List<int> } }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                DECLARATION + """
                        fnc wrong(): Messages {
                            return Messages.Reset(infer struct { reset: 1, extra: 9 });
                        }
                        """)));
    }
}

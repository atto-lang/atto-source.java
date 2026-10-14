package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NominalMessageEnumTest {
    private static final String DECLARATION = """
            define enum Messages as
                Reset struct { reset: int } 1;
                Delta struct { delta: int } 2;
                Ready 3;
            end
            """;

    @Test
    void parsesStableTaggedPayloadSchema() {
        Ast.Program program = Parser.parse(DECLARATION);
        Ast.EnumDecl declaration = (Ast.EnumDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("Messages", declaration.name());
        assertEquals(3, declaration.variants().size());
        assertEquals(1L, declaration.variants().getFirst().tag());
        assertTrue(declaration.variants().getFirst().payload().isRecordType());
        assertNull(declaration.variants().getLast().payload());
    }

    @Test
    void rejectsAmbiguousAndMissingProtocolTags() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A 1; B 1; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A 1; A 2; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A 0; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A struct { x: int }; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as end"));
    }
    @Test
    void nominalConstructorsAndExhaustiveValueMatchExecuteAsGuest() throws Exception {
        String source = DECLARATION + """
                fnc decode(Messages message): int {
                  val int selected = match message over {
                    on Messages.Reset as (payload) { return payload.reset; }
                    on Messages.Delta as (payload) { return payload.delta; }
                    on Messages.Ready as () { return 0; }
                  };
                  return selected + 1;
                }
                pub fnc main(): void {
                  stdio.println(decode(Messages.Reset(infer struct { reset: 41 })));
                  stdio.println(decode(Messages.Delta(infer struct { delta: 5 })));
                  stdio.println(decode(Messages.Ready()));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(Parser.parse(source))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "nominal-message-enum.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(program);
        }
        assertEquals("42\\n6\\n1", out.toString(StandardCharsets.UTF_8)
                .trim().replace("\\r", ""));
    }

    @Test
    void malformedConstructorsAndIncompleteVariantsFailTypeChecking() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                DECLARATION + """
                        fnc bad(Messages message): int {
                          return match message over {
                            on Messages.Reset as (payload) { return payload.reset; }
                            on Messages.Ready as () { return 0; }
                          };
                        }
                        """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                DECLARATION + """
                        fnc bad(): Messages {
                          return Messages.Reset(infer struct { delta: 12 });
                        }
                        """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                DECLARATION + """
                        fnc bad(): Messages {
                          return Messages.Missing();
                        }
                        """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                DECLARATION + """
                        fnc bad(Messages message): int {
                          return match message over {
                            on Messages.Missing as () { return 0; }
                            on _ { return 1; }
                          };
                        }
                        """)));
    }

}

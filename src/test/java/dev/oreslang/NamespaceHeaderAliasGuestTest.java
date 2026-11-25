package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native Atto guest namespace-header test: parsing alone does not count.
 * This does not enable external declared-actor code generation.
 */
final class NamespaceHeaderAliasGuestTest {
    private static final String MAIN = """
            pub routine main(): void {
                stdio.stdout.write(42);
                return;
            }
            """;

    @Test
    void headersProduceIdenticalTypedNamespace() {
        for (String header : new String[]{"namespace foo;", "use namespace foo;"}) {
            var program = Parser.parse(header + "\n" + MAIN);
            assertEquals("foo", program.namespace(), header);
            assertNotNull(TypeChecker.check(program), header);
        }
    }

    @Test
    void headersExecuteRealAttoGuestCode() throws Exception {
        for (String header : new String[]{"namespace foo;", "use namespace foo;"}) {
            var stdout = new ByteArrayOutputStream();
            Source source = Source.newBuilder(OresLanguage.ID,
                    header + "\n" + MAIN, "namespace-header-guest.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(stdout).build()) {
                context.eval(source);
            }
            assertEquals("42", stdout.toString(StandardCharsets.UTF_8), header);
        }
    }

    @Test
    void declarationOnlyActorParsesUnderBothHeaders() {
        for (String header : new String[]{"namespace foo;", "use namespace foo;"}) {
            var program = Parser.parse(header + "\ndeclare actor Counter;");
            assertEquals("foo", program.namespace(), header);
            assertEquals(1, program.modules().getFirst().declarations().size());
        }
    }

    @Test
    void duplicateAndNonflatHeadersFailClosed() {
        for (String source : new String[]{
                "namespace foo; use namespace bar; " + MAIN,
                "use namespace foo; namespace bar; " + MAIN,
                "use namespace foo; use namespace foo; " + MAIN,
                "use namespace foo.bar; " + MAIN,
                "use namespace; " + MAIN,
                "use namespace foo " + MAIN
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}

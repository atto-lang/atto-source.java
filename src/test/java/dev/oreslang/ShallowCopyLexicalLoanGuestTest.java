package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Target-profile guest gates: no disabled tests or acceptance of unrelated diagnostics. */
final class ShallowCopyLexicalLoanGuestTest {
    private static final String PERSON = """
        define class Person as
            pub let int age = 25;
            pub val Array<int> scores = new Array<int>();
        end
        """;
    private static String program(String body) {
        return PERSON + """
            pub fnc main(): void {
                let mut Person a = new Person();
                a.scores.add(10);
            """ + body + "\nreturn;\n}\n";
    }
    private static String run(String source) throws Exception {
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
            ctx.eval(Source.newBuilder(OresLanguage.ID, source, "shallow-loan.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }
    private static void reject(String source, String diagnostic) {
        var e = assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(e.getMessage().contains(diagnostic), e::toString);
    }

    @Test void readOnlyDescendantAndIndependentRootThenRestore() throws Exception {
        assertEquals("25|30|10|42", run(program("""
            block {
                let mut Person b = copy! a;
                b.age = 30;
                stdio.stdout.write(a.age);
                stdio.stdout.write("|");
                stdio.stdout.write(b.age);
                stdio.stdout.write("|");
                stdio.stdout.write(b.scores.get(0));
            }
            a.scores.set(0, 42);
            stdio.stdout.write("|");
            stdio.stdout.write(a.scores.get(0));
            """)));
    }

    @Test void rejectsMutationThroughOriginal() {
        reject(program("""
            block {
                val b = copy! a;
                a.scores.set(0, 99);
                stdio.stdout.write(b.scores.get(0));
            }
            """), "E_COPY_READONLY");
    }

    @Test void rejectsMutationThroughCopy() {
        reject(program("""
            block {
                let mut Person b = copy! a;
                b.scores.set(0, 99);
            }
            """), "E_COPY_READONLY");
    }

    @Test void nestedBlocksDoNotReleaseOuterLoan() {
        reject(program("""
            block {
                val b = copy! a;
                block {
                    val c = copy! a;
                    stdio.stdout.write(c.scores.get(0));
                }
                a.scores.set(0, 99);
                stdio.stdout.write(b.scores.get(0));
            }
            """), "E_COPY_READONLY");
    }

    @Test void moveAliasesCannotConsumeBorrowedSource() {
        for (String op : new String[]{"take!", "move!"}) {
            reject(program("""
                block {
                    val b = copy! a;
                    val moved = %s a;
                    stdio.stdout.write(b.scores.get(0));
                }
                """.formatted(op)), "E_COPY_LOAN");
        }
    }

    @Test void noDeepCopyWithoutNominalOptIn() {
        reject(program("val b = copy!! a;"), "E_COPY_OPT_IN_REQUIRED");
    }

    @Test void explicitNominalOptInEnablesIndependentDeepCopy() throws Exception {
        String source = program("""
            let mut Person b = copy!! a;
            b.scores.set(0, 99);
            stdio.stdout.write(a.scores.get(0));
            stdio.stdout.write("|");
            stdio.stdout.write(b.scores.get(0));
            """).replace("define class Person as", "define class Person implements DeepCopyable<Person> as");
        assertEquals("10|99", run(source));
    }
}

package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release-blocking regressions for the named-call tail trampoline.
 *
 * These are real guest execution cases, not pure binder shape tests.
 * A named call in direct return position must enter the same trampoline
 * as positional calls, but return through defer/finally must retain its
 * cleanup barrier. Do not mark these tests skipped to green a PR.
 */
final class NamedScalarTailCallRegressionTest {

    @Test
    void deepDirectNamedTailRecursionDoesNotConsumeHostStack() throws Exception {
        String code = """
                pub fnc descend(int remaining): int {
                    if remaining == 0; do
                        return 0;
                    else
                        return descend(remaining: remaining - 1);
                    fi
                }

                pub routine main(): void {
                    stdio.stdout.write(descend(30000));
                    return;
                }
                """;
        assertEquals("0", execute(code, "named-tail-depth.ores"));
    }

    @Test
    void directNamedTailCallDoesNotSkipDeferredCleanup() throws Exception {
        String code = """
                pub fnc leaf(int value): int {
                    stdio.stdout.write("L");
                    return value;
                }

                pub fnc with_cleanup(): int {
                    defer stdio.stdout.write("D");
                    return leaf(value: 7);
                }

                pub routine main(): void {
                    stdio.stdout.write(with_cleanup());
                    return;
                }
                """;
        assertEquals("LD7", execute(code, "named-tail-cleanup.ores"));
    }

    @Test
    void reorderedNamedTailReturnPreservesLexicalEffectsAndFormalAbi() throws Exception {
        String code = """
                pub fnc first(): int {
                    stdio.stdout.write("A");
                    return 4;
                }
                pub fnc second(): int {
                    stdio.stdout.write("B");
                    return 3;
                }
                pub fnc combine(int left, int right): int {
                    return left * 10 + right;
                }
                pub fnc tail(): int {
                    return combine(right: second(), left: first());
                }
                pub routine main(): void {
                    stdio.stdout.write(tail());
                    return;
                }
                """;
        assertEquals("BA43", execute(code, "named-tail-reordered.ores"));
    }

    @Test
    void failedFirstLexicalSourceAbortsLaterNamedArguments() throws Exception {
        String code = """
                pub fnc fails_first(): int {
                    stdio.stdout.write("B");
                    return 1 % 0;
                }
                pub fnc must_not_run(): int {
                    stdio.stdout.write("A");
                    return 4;
                }
                pub fnc combine(int left, int right): int {
                    return left * 10 + right;
                }
                pub fnc protected_call(): int {
                    try {
                        return combine(right: fails_first(), left: must_not_run());
                    } catch (err) {
                        return 9;
                    }
                }
                pub routine main(): void {
                    stdio.stdout.write(protected_call());
                    return;
                }
                """;
        assertEquals("B9", execute(code, "named-tail-exception-abort.ores"));
    }

    private static String execute(String code, String filename) throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(code)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code, filename)
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SuspendableProductDestructureTest {
    @Test
    void returnedTupleAndRecordRestDestructureAfterAwaitExactlyOnce()
            throws Exception {
        String code = """
                async fnc tuple_result(): [int, int, int] {
                    stdio.stdout.write("T");
                    return (1, 2, 3);
                }

                async fnc record_result(): record{x: int, y: int, z: int} {
                    stdio.stdout.write("R");
                    return record{z: 6, x: 4, y: 5};
                }

                async fnc consume(): int {
                    const [head, ...tail] = await tuple_result();
                    const [second, third] = tail;

                    const {x, ...rest} = await record_result();
                    const {y, z} = rest;

                    return head + second + third + x + y + z;
                }

                pub routine main(): void {
                    stdio.stdout.write(await consume());
                    return;
                }
                """;

        assertEquals("TR21", checkedExecution(code));
    }

    @Test
    void suspendedObjectRestDoesNotReintroduceSelectedMember()
            throws Exception {
        String code = """
                async fnc record_result(): record{x: int, y: int} {
                    return record{y: 23, x: 19};
                }

                async fnc consume(): int {
                    const {x, ...rest} = await record_result();
                    const {y} = rest;
                    return x * 100 + y;
                }

                pub routine main(): void {
                    stdio.stdout.write(await consume());
                    return;
                }
                """;

        assertEquals("1923", checkedExecution(code));
    }

    private static String checkedExecution(String code) throws Exception {
        Ast.Program parsed = Parser.parse(code);
        Ast.Program typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(
                    Source.newBuilder(
                                    OresLanguage.ID,
                                    code,
                                    "suspendable-product-destructure.ores")
                            .mimeType(OresLanguage.MIME_TYPE)
                            .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

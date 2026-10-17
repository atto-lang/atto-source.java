package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class RecordTupleLambdaRuntimeRegressionTest {
    @Test
    void runtimeDistinguishesScalarTupleAndRecordShapeProjection() throws Exception {
        String program = """
                type Mapper = (int) => int;
                pub fnc main(): void {
                  const multiply = |int n|: int -> n * 3;
                  const mapper: Mapper = |int n| -> (n * 3) as int;
                  const point = struct{x: int, y: int}{x: 7, y: 9};
                  const xOnly = point as {x: int};
                  const pair = tuple(multiply(7), xOnly.x) as Tuple[int, int];
                  stdio.stdout.write(pair[0]);
                  stdio.stdout.write("|");
                  stdio.stdout.write(pair[1]);
                  stdio.stdout.write("|");
                  stdio.stdout.write(mapper(5));
                  return;
                }
                """;
        var checked = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(checked));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "record-tuple-lambda.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        assertEquals("21|7|15", out.toString(StandardCharsets.UTF_8));
    }
}

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

final class AsyncStdioDeferTest {
    @Test
    void braceDeferAwaitResumesThroughAsyncMainContinuation() throws Exception {
        String result = run("""
                pub async routine main(): void {
                  defer {
                    await stdio.flush_async_logs();
                  }
                  stdio.stdout.log_async("from-main");
                }
                """);
        assertEquals("from-main\n", result);
    }

    @Test
    void inlineSelfInvokingDeferCapturesArgumentsAtRegistration() throws Exception {
        String result = run("""
                pub routine main(): void {
                  let String x = "old";
                  defer (|saved| -> {
                    stdio.stdout.log(saved);
                  })(x);
                  x = "new";
                }
                """);
        assertEquals("old\n", result);
    }

    @Test
    void deferredBlocksRunInReverseRegistrationOrder() throws Exception {
        String result = run("""
                pub routine main(): void {
                  defer { stdio.stdout.log("first"); }
                  defer { stdio.stdout.log("second"); }
                }
                """);
        assertEquals("second\nfirst\n", result);
    }

    @Test
    void inlineDeferredLambdaCannotStoreBorrowArgument() {
        String program = """
                pub routine main(): void {
                  val owned = arr[1, 2];
                  defer (|x| -> {
                    stdio.stdout.log("cleanup");
                  })(rt borrow owned);
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains("defer cannot retain borrowed"));
    }

    private static String run(String code) throws Exception {
        TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(Parser.parse(code));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "async-stdio-defer.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}

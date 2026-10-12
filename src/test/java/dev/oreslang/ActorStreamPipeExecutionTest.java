package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class ActorStreamPipeExecutionTest {
    private static String program() throws Exception {
        return Files.readString(Path.of("examples/actor-pipeline-15.ores"));
    }

    private static String execute(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, text, "actorstream-15.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test void fifteenActorsCompileAndRoundTripWithExplicitStaticDelimiter() throws Exception {
        assertEquals("PIPE_15_RESULT_32", execute(program()));
    }

    @Test void fifteenActorsCompileAndRoundTripWithDottedStaticCall() throws Exception {
        String source = program().replace("ActorUtils::pipe(", "ActorUtils.pipe(");
        assertEquals("PIPE_15_RESULT_32", execute(source));
    }

    @Test void fifteenActorsCompileAndRoundTripWithInfixPipe() throws Exception {
        String source = program();
        String from = source.substring(source.indexOf("    return ActorUtils::pipe("),
                source.indexOf("\n}", source.indexOf("    return ActorUtils::pipe(")));
        String to = "    return " + String.join(" pipe ", java.util.Arrays.asList(
                "spawn Source()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Transform()",
                "spawn Sink()")) + ";";
        assertEquals("PIPE_15_RESULT_32", execute(source.replace(from,to)));
    }

    @Test void staticResolutionDelimiterWorksForOrdinaryClassStaticFunctions() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Utility as
                    static fnc answer(): int { return 42; }
                end
                pub fnc main(): int { return Utility::answer(); }
                """));
    }

    @Test void rejectDuplicateActorCapabilityWithoutRunningGuestCallbacks() throws Exception {
        String original = program();
        String malformed = original.replace(
                "return ActorUtils::pipe(", "return ActorUtils::pipe(")
                .replace("spawn Source(),\n        spawn Transform()",
                    "spawn Source(),\n        spawn Source()");
        assertThrows(PolyglotException.class, () -> execute(malformed));
    }

    @Test void rejectNonActorStageAtTypechecking() throws Exception {
        String malformed = program().replace(
                "spawn Transform(),", "123,");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(malformed)));
    }
}

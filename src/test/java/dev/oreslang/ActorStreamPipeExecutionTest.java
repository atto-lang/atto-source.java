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

    private static String symbolicFifteenActorSource() throws Exception {
        String source = program();
        int start = source.indexOf("    return ActorUtils::pipe(");
        int end = source.indexOf("\n}", start);
        if (start < 0 || end < 0) throw new IllegalStateException("fixture factory changed");
        java.util.ArrayList<String> stages = new java.util.ArrayList<>();
        stages.add("spawn Source()");
        for (int i = 0; i < 13; i++) stages.add("spawn Transform()");
        stages.add("spawn Sink()");
        return source.substring(0, start)
                + "    return " + String.join(" | ", stages) + ";"
                + source.substring(end);
    }

    @Test void fifteenActorsCompileAndRoundTripWithSymbolicActorPipe() throws Exception {
        assertEquals("PIPE_15_RESULT_32", execute(symbolicFifteenActorSource()));
    }

    @Test void symbolicPipeRejectsNonActorStageBeforeAnyGuestIngress() throws Exception {
        String invalid = symbolicFifteenActorSource().replace(" | spawn Sink();", " | 123;");
        assertNotEquals(symbolicFifteenActorSource(), invalid);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
    }

    @Test void integerBitwiseOrStaysIntegerBitwiseOr() throws Exception {
        assertEquals("7", execute("""
                pub async routine main(): void {
                    stdio.stdout.write(5 | 2);
                    return;
                }
                """));
    }


    private static String threeActorNamedPipelines() {
        return """
                define enum Signal as
                    Data: int = 1
                    Result: int = 2
                end
                define pattern PipeMessages as
                    Signal.Data
                    Signal.Result
                end
                define actor Source as
                    receive(ActorMail<PipeMessages> mail): void {
                        match mail.value over
                            on Signal.Data n -> {
                                self.forward(Signal.Data(n));
                            }
                            on Signal.Result n -> {
                                self.end();
                            }
                        end
                        return;
                    }
                end
                define actor Transform as
                    receive(ActorMail<PipeMessages> mail): void {
                        match mail.value over
                            on Signal.Data n -> {
                                self.forward(Signal.Data(n + 1));
                            }
                            on Signal.Result n -> {
                                self.backward(Signal.Result(n));
                                self.end();
                            }
                        end
                        return;
                    }
                end
                define actor Sink as
                    receive(ActorMail<PipeMessages> mail): void {
                        match mail.value over
                            on Signal.Data n -> {
                                stdio.stdout.write(n);
                                self.backward(Signal.Result(n));
                                self.end();
                            }
                            on Signal.Result n -> {
                                self.end();
                            }
                        end
                        return;
                    }
                end
                pub async routine main(): void {
                    val sourceOne = spawn Source();
                    val transformOne = spawn Transform();
                    val sinkOne = spawn Sink();
                    val symbolic = sourceOne | transformOne | sinkOne;
                    symbolic.send(Signal.Data(41));
                    await symbolic.get_done();

                    val sourceTwo = spawn Source();
                    val transformTwo = spawn Transform();
                    val sinkTwo = spawn Sink();
                    val keyword = sourceTwo pipe transformTwo pipe sinkTwo;
                    keyword.send(Signal.Data(42));
                    await keyword.get_done();
                    return;
                }
                """;
    }

    @Test void namedActorRefsComposeWithBothPipeSpellingsAndPhysicallyFinish() throws Exception {
        assertEquals("4243", execute(threeActorNamedPipelines()));
    }

    @Test void mixedSymbolicAndKeywordPipeStagesAreOneActorGraph() throws Exception {
        String source = threeActorNamedPipelines()
                .replace("sourceOne | transformOne | sinkOne",
                        "sourceOne | transformOne pipe sinkOne");
        assertNotEquals(threeActorNamedPipelines(), source);
        assertEquals("4243", execute(source));
    }

    @Test void namedSymbolicPipeRejectsScalarStageAtTypechecking() {
        String invalid = threeActorNamedPipelines()
                .replace("sourceOne | transformOne | sinkOne",
                        "sourceOne | 123 | sinkOne");
        assertNotEquals(threeActorNamedPipelines(), invalid);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
    }

    @Test void integerVariableBitwiseOrKeepsItsOriginalMeaning() throws Exception {
        assertEquals("77", execute("""
                pub async routine main(): void {
                    val lhs = 5;
                    val rhs = 2;
                    stdio.stdout.write(lhs | rhs);
                    stdio.stdout.write(5 | 2);
                    return;
                }
                """));
    }

    @Test void staticResolutionDelimiterWorksForOrdinaryClassStaticFunctions() throws Exception {
        assertEquals("42", execute("""
                define class Utility as
                    pub static fnc answer(): int { return 42; }
                end
                pub async routine main(): void {
                    val Utility = 17;
                    stdio.stdout.write(Utility::answer());
                    return;
                }
                """));
    }

    @Test void staticDelimiterDisambiguatesShadowedActorUtils() throws Exception {
        String source = program().replace(
                "return ActorUtils::pipe(",
                "val ActorUtils = 17;\n    return ActorUtils::pipe(");
        assertEquals("PIPE_15_RESULT_32", execute(source));
        assertThrows(PolyglotException.class,
                () -> execute(source.replace("ActorUtils::pipe(", "ActorUtils.pipe(")));
    }

    @Test void rejectReusingOneActorCapabilityAtRuntime() throws Exception {
        String malformed = program().replace(
                "return ActorUtils::pipe(\n        spawn Source(),\n        spawn Transform(),",
                "val repeated = spawn Source();\n    return ActorUtils::pipe(\n        repeated,\n        repeated,");
        assertNotEquals(program(), malformed);
        PolyglotException rejected = assertThrows(PolyglotException.class,
                () -> execute(malformed));
        assertTrue(rejected.toString().contains("distinct same-runtime"),
                rejected.toString());
    }

    @Test void rejectNonActorStageAtTypechecking() throws Exception {
        String malformed = program().replace(
                "spawn Transform(),", "123,");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(malformed)));
    }

    @Test void prematureTransformStopFailsTheWholeActorStreamDoneBarrier() throws Exception {
        String broken = program().replace(
                "self.forward(Signal.Data(n + 1));",
                "self.end();");
        assertNotEquals(program(), broken);
        assertThrows(PolyglotException.class, () -> execute(broken),
                "an early middle-stage STOP must never complete ActorStream.get_done successfully");
    }

    @Test void rejectMoreThanSixteenActorsBeforeAnyRuntimeAdmission() throws Exception {
        String source = program().replace(
                "spawn Sink()",
                "spawn Transform(),\n        spawn Transform(),\n        spawn Sink()");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "ActorStream caps its actor count before spawning any stages");
    }

    @Test void explicitStaticResolutionDoesNotTurnPrivateMethodsPublic() throws Exception {
        String source = """
                define class Utility as
                    static fnc secret(): int { return 42; }
                end
                pub async routine main(): void {
                    stdio.stdout.write(Utility::secret());
                    return;
                }
                """;
        assertThrows(PolyglotException.class, () -> execute(source));
    }

    private static String resizePipeline(int transforms) throws Exception {
        String original = program();
        int start = original.indexOf("    return ActorUtils::pipe(");
        int end = original.indexOf("\n}", start);
        if (start < 0 || end < 0) throw new IllegalStateException("fixture factory changed");
        java.util.ArrayList<String> stages = new java.util.ArrayList<>();
        stages.add("spawn Source()");
        for (int i = 0; i < transforms; i++) stages.add("spawn Transform()");
        stages.add("spawn Sink()");
        int value = 6 + transforms * 2;
        int actors = transforms + 2;
        return (original.substring(0, start)
                + "    return ActorUtils::pipe(" + String.join(", ", stages) + ");"
                + original.substring(end))
                .replace("if n == 32 then", "if n == " + value + " then")
                .replace("PIPE_15_RESULT_32", "PIPE_" + actors + "_RESULT_" + value);
    }

    @Test void minimumTwoActorPipelineDoesRealForwardBackwardDelivery() throws Exception {
        assertEquals("PIPE_2_RESULT_6", execute(resizePipeline(0)));
    }

    @Test void maximumSixteenActorPipelineDoesRealForwardBackwardDelivery() throws Exception {
        assertEquals("PIPE_16_RESULT_34", execute(resizePipeline(14)));
    }

    @Test void reservedPipelineBuiltinsCannotBeHijackedByLocalClasses() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class ActorUtils as
                    pub static fnc pipe(): int { return 42; }
                end
                pub fnc main(): int { return ActorUtils::pipe(); }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class ActorStream as
                    pub fnc f(): int { return 42; }
                end
                pub fnc main(): int { return 5; }
                """)));
    }

    @Test void tooManyInfixPipelineStagesFailBeforeGuestStageCreation() throws Exception {
        String source = program();
        int start = source.indexOf("    return ActorUtils::pipe(");
        int end = source.indexOf("\n}", start);
        java.util.ArrayList<String> stages = new java.util.ArrayList<>();
        stages.add("spawn Source()");
        for (int i = 0; i < 128; i++) stages.add("spawn Transform()");
        stages.add("spawn Sink()");
        String malformed = source.substring(0,start) + "    return "
                + String.join(" pipe ", stages) + ";" + source.substring(end);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(malformed)));
        assertTrue(rejected.toString().contains("2..16"), rejected.toString());
    }
}

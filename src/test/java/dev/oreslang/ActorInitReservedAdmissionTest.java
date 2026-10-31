package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed acceptance floor for the synchronous, scalar-only actor
 * init vertical slice. ActorRef/capability injection remains fail-closed.
 * This source file is NOT a CI or guest pass until JUnit actually executes.
 */
final class ActorInitReservedAdmissionTest {

    private static final String ACTOR = """
            define actor Worker as
              state {
                val int seed;
                let int phase;
              }

              init(seed: int) -> state {
                return state { seed: seed, phase: 0 };
              }

              receive(ActorMail<String> mail): void {
                let String observed = "wrong";
                self.handle_state(critical[read] |s| -> {
                  if s.seed == 7; do
                    observed = "initialized";
                  fi
                });
                stdio.stdout.write(observed);
                self.end();
                return;
              }
            end
            """;

    private static String main(String spawn) {
        return ACTOR + """
                pub async routine main(): void {
                  val worker = await %s;
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """.formatted(spawn);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void scalarNamedSpawnBuildsCompleteActorStateBeforeGuestReceive() throws Exception {
        String program = main("spawn Worker(seed: 7)");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "actor-init-scalar.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("initialized", output.toString(StandardCharsets.UTF_8));
    }

    private static void rejectsTyped(String program, String expectedFragment) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(failure.getMessage().contains(expectedFragment), failure.getMessage());
    }

    @Test
    void missingOrUnknownInitLabelsAreRejected() {
        rejectsTyped(main("spawn Worker()"), "all init parameters");
        rejectsTyped(main("spawn Worker(other: 7)"), "label");
    }

    @Test
    void duplicateNamedSpawnArgumentIsRejected() {
        rejectsTyped(main("spawn Worker(seed: 7, seed: 8)"), "all init parameters");
    }

    @Test
    void wrongNamedSpawnArgumentTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(main("spawn Worker(seed: \"no\")")));
    }

    @Test
    void positionalSpawnParametersDoNotBypassNames() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(main("spawn Worker(7)")));
        assertTrue(failure.getMessage().contains("named"), failure.getMessage());
    }

    @Test
    void incompleteStateInitializerRejectedBeforeExecution() {
        rejectsTyped(ACTOR.replace("seed: seed, phase: 0", "seed: seed"),
                "every declared state field");
    }

    @Test
    void repeatedOrUnknownStateFieldRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(ACTOR.replace(
                        "seed: seed, phase: 0", "seed: seed, seed: seed")));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(ACTOR.replace(
                        "seed: seed, phase: 0", "seed: seed, other: 0")));
    }

    @Test
    void initCannotSuspendOrInvokeOtherFunctions() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(ACTOR.replace(
                        "init(seed: int)", "async init(seed: int)")));
        rejectsTyped(ACTOR.replace(
                "phase: 0", "phase: seed + 1"),
                "scalar literal");
        rejectsTyped(ACTOR.replace(
                "phase: 0", "phase: print(seed)"),
                "scalar literal");
    }

    @Test
    void competingOnStartAndInitAreForbidden() {
        rejectsTyped(ACTOR.replace("  receive(", "  on_start(): void { return; }\n  receive("),
                "competing startup hooks");
    }

    @Test
    void stateLiteralCannotEscapeReservedInit() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                          val record = state { phase: 0 };
                          return;
                        }
                        """));
    }

    @Test
    void actorRefInitTransferRemainsFailClosed() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Store as
                          state { let int x = 0; }
                          receive(ActorMail<String> mail): void {
                            self.end();
                            return;
                          }
                        end
                        define actor NeedsStore as
                          state { val ActorRef<Store> store; }
                          init(store: ActorRef<Store>) -> state {
                            return state { store: store };
                          }
                          receive(ActorMail<String> mail): void {
                            self.end();
                            return;
                          }
                        end
                        """));
        assertTrue(rejected.getMessage().contains("scalar ABI"),
                rejected.getMessage());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void namedArgumentsBindByLabelNotCallerPosition() throws Exception {
        String twoParameterActor = ACTOR
                .replace("init(seed: int)", "init(phase: int, seed: int)")
                .replace("phase: 0", "phase: phase")
                .replace("observed = \"initialized\";",
                        "if s.phase == 3; do observed = \"initialized\"; fi");
        String program = main("spawn Worker(seed: 7, phase: 3)")
                .replace(ACTOR, twoParameterActor);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program,
                "actor-init-named-order.ores").mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("initialized", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void initializedValDependencyCannotBeReboundInsideCriticalWrite() {
        String source = ACTOR.replace(
                "self.handle_state(critical[read] |s| -> {",
                "self.handle_state(critical[write] |s| -> {"
                        + " s.seed = 8; });\\n"
                        + " self.handle_state(critical[read] |s| -> {")
                .replace("\\n", "\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("immutable"), failure.getMessage());
    }

    @Test
    void stateLoanComputedWritesAreNotAnImmutableFieldEscape() {
        String source = ACTOR.replace(
                "self.handle_state(critical[read] |s| -> {",
                "self.handle_state(critical[write] |s| -> {"
                        + " s[\"seed\"] = 8; });\\n"
                        + " self.handle_state(critical[read] |s| -> {")
                .replace("\\n", "\n");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void initStateFieldTypeMismatchIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        ACTOR.replace("phase: 0", "phase: true")));
    }

    @Test
    void spawnHasNoUncheckedEffectfulOrSuspendingOperandPath() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        main("spawn Worker(seed: 1 + 6)")));
    }

    @Test
    void initCannotExecuteTrailingStatementsAfterCompleteState() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        ACTOR.replace("return state { seed: seed, phase: 0 };",
                                "return state { seed: seed, phase: 0 }; return;")));
    }

    @Test
    void bareSpawnCannotExposeProvisionalActorInitReference() {
        String source = ACTOR + """
                pub async routine main(): void {
                  val worker = spawn Worker(seed: 7);
                  await worker.get_ready();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """;
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(rejection.getMessage().contains("await spawn"),
                rejection.getMessage());
    }

    @Test
    void awaitAnotherExpressionCannotAuthorizeNestedProvisionalSpawn() {
        String source = ACTOR + """
                pub async routine main(): void {
                  val worker = await Some(spawn Worker(seed: 7));
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void aggregateStateFieldsCannotSlipThroughScalarInitAbi() {
        String source = ACTOR.replace("let int phase;", "let Option<int> phase;");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("scalar-only state ABI"),
                failure.getMessage());
    }

    @Test
    void ordinaryClassInitMethodIsNotReserved() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Ordinary as
                  init(): void { return; }
                end
                """));
    }

    @Test
    void actorConstructorIsStillForbidden() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Legacy as
                          constructor(): void { return; }
                        end
                        """));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"),
                failure.getMessage());
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end and admission invariants for the three equivalent immediate
 * actor-spawn spellings. This does NOT enable bare actor construction,
 * constructor arguments, or configurable ActorSpec values.
 */
final class ActorSpawnNewSyntaxTest {
    private static final String DECLARATION = """
            define actor Worker as
              let int starts;

              on_start(): void {
                self.starts = 1;
                return;
              }

              receive(ActorMail<String> mail): void {
                self.send(self.starts);
                self.end();
                return;
              }
            end
            """;

    @Test
    void directAndExplicitNewAndParenthesizedNewHaveIdenticalAst() {
        String prefix = DECLARATION + "\npub routine main(): void { val a = ";
        String suffix = "; return; }";
        Ast.Program direct = Parser.parse(prefix + "spawn Worker()" + suffix);
        assertEquals(direct, Parser.parse(prefix + "spawn new Worker()" + suffix));
        assertEquals(direct, Parser.parse(prefix + "spawn (new Worker())" + suffix));
        assertDoesNotThrow(() -> TypeChecker.check(direct));
        assertDoesNotThrow(() -> TypeChecker.check(
                Parser.parse(prefix + "spawn new Worker()" + suffix)));
        assertDoesNotThrow(() -> TypeChecker.check(
                Parser.parse(prefix + "spawn (new Worker())" + suffix)));
    }

    @Test
    void threeSpellingsRunSameLifecycleInSeparateActors() throws Exception {
        String program = DECLARATION + """
                pub async routine main(): void {
                  val a = spawn Worker();
                  val b = spawn new Worker();
                  val c = spawn (new Worker());

                  await a.ready;
                  await b.ready;
                  await c.ready;

                  a.send("start");
                  b.send("start");
                  c.send("start");
                  val av = (await a.outputs.next()).value.unwrap();
                  val bv = (await b.outputs.next()).value.unwrap();
                  val cv = (await c.outputs.next()).value.unwrap();
                  stdio.stdout.write(av.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(bv.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(cv.value);
                  await a.done;
                  await b.done;
                  await c.done;
                  return;
                }
                """;
        assertEquals("1|1|1", run(program));
    }

    @Test
    void explicitNewWorksWithPrivateAndUntrustedActorKinds() throws Exception {
        String program = """
                define isoactor Isolated as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                define untrusted actor Restricted as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val privateActor = spawn new Isolated();
                  val untrustedActor = spawn (new Restricted());
                  await privateActor.ready;
                  await untrustedActor.ready;
                  privateActor.send("done");
                  untrustedActor.send("done");
                  await privateActor.done;
                  await untrustedActor.done;
                  stdio.stdout.write("isolated");
                  return;
                }
                """;
        assertEquals("isolated", run(program));
    }

    @Test
    void nonActorClassCannotBeSpawnedThroughExplicitNew() {
        String program = """
                define class Worker as
                end
                pub routine main(): void {
                  val ref = spawn new Worker();
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains("ordinary class"), error.getMessage());
    }

    @Test
    void actorSpawnArgumentsStayRejectedInAllFormsUntilConstructorsExist() {
        String prefix = DECLARATION + "\npub routine main(): void { val a = ";
        String suffix = "; return; }";
        for (String spelling : new String[] {
                "spawn Worker(42)", "spawn new Worker(42)", "spawn (new Worker(42))"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(prefix + spelling + suffix)),
                    spelling);
            assertTrue(error.getMessage().contains("does not accept constructor/startup arguments"),
                    spelling + ": " + error.getMessage());
        }
    }

    @Test
    void noAccidentalNestedNewOrAmbiguousParenthesizedCall() {
        String prefix = DECLARATION + "\npub routine main(): void { val a = ";
        String suffix = "; return; }";
        for (String invalid : new String[]{
                "spawn new new Worker()",
                "spawn (Worker())",
                "spawn (new Worker()",
                "spawn new Worker("
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(prefix + invalid + suffix), invalid);
        }
    }

    @Test
    void actorConstructorsAndBareNewActorRemainUnavailable() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  constructor(int number) { }
                end
                """));
        String program = DECLARATION + """
                pub routine main(): void {
                  val unused = new Worker();
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("outside spawn"), failure.getMessage());
        assertTrue(failure.getMessage().contains("spawn new Worker()"), failure.getMessage());
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "actor-spawn-new-equivalence.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

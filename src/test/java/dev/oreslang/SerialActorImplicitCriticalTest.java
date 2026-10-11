package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full source admission and synchronous guest execution for inferred critical.
 * HTTP and suspended actor state are NOT covered by the single-mailbox smoke.
 */
final class SerialActorImplicitCriticalTest {
    private static final String SERIAL_COUNTER = """
            define serial actor Counter as
                state { count: int = 0; }
                pub increment(): int using state.read, state.write {
                    return self.handle_state(|s| -> {
                        s.count = s.count + 1;
                        return s.count;
                    });
                }
                pub get_count(): int using state.read {
                    return self.handle_state(|s| -> s.count);
                }
                receive(ActorMail<String> mail): void {
                    val next = self.increment();
                    if (next == 3) {
                        stdio.println(self.get_count());
                        self.end();
                    } fi
                    return;
                }
            end
            pub async routine main(): void {
                val worker = spawn Counter();
                await worker.ready;
                worker.send("one");
                worker.send("two");
                worker.send("three");
                await worker.done;
                return;
            }
            """;

    @Test void serialShorthandAndBuiltinSuperclassPreserveTypeAndEffects() {
        Ast.Program program = Parser.parse(SERIAL_COUNTER);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(klass.serial());
        assertNotNull(ActorStateContract.stateField(klass));
        assertDoesNotThrow(() -> ActorStateContract.verify(program));
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(program));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));

        // Serial is a scheduling modifier; ordinary actor isolation is orthogonal.
        // The explicit 'from serial actor' spelling remains in #547's separate
        // builtin-superclass branch and is not advertised by this parser yet.
        String explicit = SERIAL_COUNTER.replace("|s|", "critical |s|");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(explicit)));
    }

    @Test void ordinaryActorDoesNotGainImplicitCriticalAuthority() {
        String ordinary = SERIAL_COUNTER.replace("define serial actor Counter", "define actor Counter");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(ordinary)));
        assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(ordinary)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(ordinary)));
    }

    @Test void serialStillRejectsMissingEffectAndEscapedLoans() {
        String understated = SERIAL_COUNTER.replace("using state.read, state.write", "using state.read");
        assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(understated)));
        String escaped = SERIAL_COUNTER.replace(
                "self.handle_state(|s| -> s.count)", "self.handle_state(|s| -> s)");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(escaped)));
        String nonlexical = SERIAL_COUNTER.replace("|s|", "nlex |s|");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(nonlexical)));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(SERIAL_COUNTER.replace(
                        "define serial actor Counter",
                        "define serial actor Counter from serial actor")));
    }

    @Test void guestSerialCounterPreservesOneReceiveAcrossCooperation() throws Exception {
        // The actor's logical handler lifetime must survive a physical
        // carrier handoff; the next user mail is not admitted early.
        String source = SERIAL_COUNTER.replace(
                "val next = self.increment();",
                "val next = self.increment();\n                    rt cooperate;");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source compiled = Source.newBuilder(
                    OresLanguage.ID, source, "serial-cooperation.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(compiled));
        }
        assertEquals("3\\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test void serialHttpRegistrationFailsClosedWhileAdapterSpawnsPerRequest() throws Exception {
        String source = """
                define serial actor Counter as
                    receive(ActorMail<String> mail): void { return; }
                end
                pub routine main(): void {
                    val handler = http.handler("Counter");
                    return;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Source compiled = Source.newBuilder(
                    OresLanguage.ID, source, "serial-http-must-be-persistent.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            Exception failure = assertThrows(Exception.class,
                    () -> context.eval(compiled));
            String details = String.valueOf(failure.getMessage());
            assertTrue(details.contains("serial HTTP handler requires persistent"),
                    "serial HTTP must fail closed, not spawn one actor per request: " + details);
        }
    }

    @Test void guestSerialCounterExecutesWithoutCriticalKeyword() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source source = Source.newBuilder(
                    OresLanguage.ID, SERIAL_COUNTER, "serial-implicit-critical.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals("3\n", output.toString(StandardCharsets.UTF_8));
    }
}

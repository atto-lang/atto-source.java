package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defense in depth: a malformed/prevalidated AST must not commit channel I/O
 * before the runtime enforces the static nonblocking actor-domain boundary.
 */
final class StaticSelectPrecommitRuntimeAdmissionTest {
    @Test
    void forgedNonActorSelectCannotEvaluateCandidateBeforeAdmission()
            throws Exception {
        for (String form : new String[] {
                "nb select {",
                "do nb select {",
                "nb select over",
                "do nb select over"
        }) {
            boolean endDelimited = form.endsWith("over");
            String source = """
                    fnc side_effect_payload(): int {
                      stdio.stdout.write("operand-executed");
                      return 42;
                    }
                    pub fnc main(): void {
                      val Channel<int> ready = Channel.new<int>(1);
                    """ + "  " + form + """
                        case writech ready, side_effect_payload(): {
                          stdio.stdout.write("arm-executed");
                        }
                    """ + (endDelimited ? "  end\n" : "  }\n") + """
                      return;
                    }
                    """;

            Ast.Program forged = Parser.parse(source);
            IllegalArgumentException staticallyRejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(forged));
            assertTrue(staticallyRejected.getMessage().contains("actor execution domain"));

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.initialize(OresLanguage.ID);
                context.enter();
                try {
                    // Deliberately bypass OresLanguage.parse(), which normally
                    // typechecks before building this executable root.
                    RuntimeException failure = assertThrows(
                            RuntimeException.class,
                            () -> new OresEvalRootNode(null, forged)
                                    .getCallTarget().call());
                    StringBuilder reasons = new StringBuilder();
                    for (Throwable current = failure; current != null;
                            current = current.getCause()) {
                        reasons.append(current).append('\n');
                    }
                    assertTrue(reasons.toString().contains("actor execution"),
                            reasons::toString);
                } finally {
                    context.leave();
                }
            }
            assertEquals("", output.toString(StandardCharsets.UTF_8),
                    form + " evaluated a candidate despite failed actor admission");
        }
    }
}

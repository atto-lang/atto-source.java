package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Combined integration gate for the two originally independent feature PRs.
 * Receiver syntax must not create additional slots, and lexical captures
 * cannot launder a sealed or private-abstract invocation capability.
 */
final class CombinedReceiverHookCaptureTest {

    @Test
    void explicitSelfOverrideStillUsesPrivateAbstractOriginAndOneMethodSlot() throws Exception {
        String program = """
                define abstract class Framework as
                  private abstract handle(int value): int;
                  pub dispatch(int value): int {
                    return self.handle(value);
                  }
                end
                define class Implementation extends Framework as
                  private handle(self, int value): int {
                    return value + 7;
                  }
                end
                pub routine main(): void {
                  val instance = new Implementation();
                  stdio.stdout.write(instance.dispatch(5));
                  return;
                }
                """;
        Ast.Program ast = Parser.parse(program);
        assertDoesNotThrow(() -> TypeChecker.check(ast));
        assertDoesNotThrow(() -> OwnershipChecker.check(ast));
        assertEquals("12", run(program));
    }

    @Test
    void methodReceiverSpellingCannotBeUsedAsAnOverloadEscape() {
        for (String declarations : new String[] {
                """
                private handle(self, int value): int { return value; }
                private handle(int value): int { return value; }
                """,
                """
                private handle(int value): int { return value; }
                private handle(self, int value): int { return value; }
                """
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "define class Sample as\n" + declarations + "\nend")),
                    "a receiver marker must not create a second method body");
        }
    }

    @Test
    void privateAbstractOverridesCannotBecomePublicThroughExplicitSelf() {
        String program = """
                define abstract class Framework as
                  private abstract handle(int value): int;
                end
                define class Implementation extends Framework as
                  pub handle(self, int value): int { return value; }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("private")
                        || failure.getMessage().contains("public"),
                failure::getMessage);
    }

    @Test
    void denylistedSelfMayStillCaptureOrdinaryValuesInOverridingMethod() throws Exception {
        String program = """
                define abstract class Framework as
                  private abstract handle(int value): int;
                  pub dispatch(int value): int {
                    return self.handle(value);
                  }
                end
                define class Implementation extends Framework as
                  private handle(self, int value): int {
                    val callback = nlex[self] || -> value + 7;
                    return callback();
                  }
                end
                pub routine main(): void {
                  val instance = new Implementation();
                  stdio.stdout.write(instance.dispatch(5));
                  return;
                }
                """;
        Ast.Program ast = Parser.parse(program);
        assertDoesNotThrow(() -> TypeChecker.check(ast));
        assertDoesNotThrow(() -> OwnershipChecker.check(ast));
        assertEquals("12", run(program));
    }

    @Test
    void allowlistedSelfCannotLaunderBorrowedReceiverIntoClosure() {
        String program = """
                define abstract class Framework as
                  private abstract handle(int value): int;
                  pub dispatch(int value): int { return self.handle(value); }
                end
                define class Implementation extends Framework as
                  private handle(self, int value): int { return value + 1; }
                  pub callback(self): void {
                    val fn = lex[self] || -> self.dispatch(3);
                    return;
                  }
                end
                """;
        Ast.Program ast = Parser.parse(program);
        assertDoesNotThrow(() -> TypeChecker.check(ast),
                "lex[self] admits the name for type resolution only");
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(ast),
                "borrowed self must not escape as an ordinary closure capture");
    }

    @Test
    void denylistedSelfCannotInvokePrivateAbstractSlot() {
        String program = """
                define abstract class Framework as
                  private abstract handle(int value): int;
                  pub dispatch(int value): int { return self.handle(value); }
                end
                define class Implementation extends Framework as
                  private handle(self, int value): int {
                    val callback = nlex[self] || -> self.handle(value);
                    return callback();
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains("self")
                        || error.getMessage().contains("unknown name"),
                error::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "combined-self.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

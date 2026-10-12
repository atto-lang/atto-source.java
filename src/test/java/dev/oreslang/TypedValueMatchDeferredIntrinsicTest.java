package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deferred expressions execute at scope exit, so built-in effect admission
 * must account for lexical declarations made *after* defer registration.
 */
final class TypedValueMatchDeferredIntrinsicTest {
    @Test
    void laterScalarBindingCannotRetargetAnApprovedDeferredIntrinsic() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        fnc classify(int n): int {
                          return match<ret=int> n over {
                            default {
                              defer stdio.println("wrong target");
                              val stdio = 7;
                              return n;
                            }
                          };
                        }
                        """)));
        assertTrue(rejected.getMessage().contains("synchronous stdio.print/println"),
                () -> "Unexpected admission diagnostic: " + rejected.getMessage());
    }

    @Test
    void laterScalarBindingCannotRetargetNestedDeferredIntrinsic() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        fnc classify(int n): int {
                          return match<ret=int> n over {
                            default {
                              if n > 0 {
                                defer stdio.println("wrong target");
                                val stdio = 7;
                                return n;
                              } else {
                                return 0;
                              } fi
                            }
                          };
                        }
                        """)));
        assertTrue(rejected.getMessage().contains("synchronous stdio.print/println"),
                () -> "Unexpected nested admission diagnostic: " + rejected.getMessage());
    }

    @Test
    void immediateIntrinsicCallBeforeLaterBindingIsStillValid() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, """
                fnc classify(int n): int {
                  return match<ret=int> n over {
                    default {
                      stdio.println("immediate");
                      val stdio = 7;
                      return n;
                    }
                  };
                }
                pub fnc main(): void { stdio.println(classify(42)); }
                """, "typed-match-immediate-intrinsic.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(guest);
        }
        assertEquals("immediate\n42",
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }
}

package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class StructuredTaskScopeV2Test {
    @Test
    void taskScopeImplicitlyJoinsUnawaitedChild() throws Exception {
        assertEquals("child|parent", run("""
                pub async fnc main(): void {
                  task scope {
                    let Future<void> child = task {
                      stdio.stdout.write("child");
                      return;
                    };
                  }
                  stdio.stdout.write("|parent");
                  return;
                }
                """));
    }

    @Test
    void nbScopeIsCompatibilitySpellingForSameStructuredBarrier() throws Exception {
        assertEquals("7|done", run("""
                pub async fnc main(): void {
                  nb scope {
                    let Future<void> child = task {
                      stdio.stdout.write(7);
                      return;
                    };
                  }
                  stdio.stdout.write("|done");
                  return;
                }
                """));
    }

    @Test
    void unawaitedChildFailureEscapesStructuredScopeAfterDrain() throws Exception {
        String program = """
                pub async fnc main(): void {
                  task scope {
                    let Future<void> child = task {
                      None.unwrap();
                      return;
                    };
                  }
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "structured-task-failure-v2.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        Throwable failure;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            failure = assertThrows(Throwable.class, () -> context.eval(source));
        }
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("Option::unwrap"),
                failure::getMessage);
    }

    @Test
    void structuredScopeExitMustBeDeclaredAsSuspension() {
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          task scope {
                            let Future<void> child = task { return; };
                          }
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("suspension point")
                        || failure.getMessage().contains("async or suspends"),
                failure::getMessage);
    }

    @Test
    void ordinaryBorrowCaptureRemainsRejectedInsideStructuredScope() {
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Box as
                          pub let int value = 1;
                        end
                        pub async fnc main(): void {
                          let Box owner = new Box();
                          val loan = rt borrow owner;
                          task scope {
                            let Future<int> child = task {
                              return loan.value;
                            };
                          }
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"),
                failure::getMessage);
    }

    @Test
    void actorStructuredScopeRemainsFailClosed() {
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define actor Worker as
                          receive(ActorMail<int> mail): void {
                            task scope {
                              let Future<void> child = task { return; };
                            }
                            return;
                          }
                        end
                        """));
        assertTrue(failure.getMessage().contains("actor"),
                failure::getMessage);
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "structured-task-scope-v2.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}

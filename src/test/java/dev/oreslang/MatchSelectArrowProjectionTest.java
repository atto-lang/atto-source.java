package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Shared slim-arrow arm grammar and record projection ownership contract. */
final class MatchSelectArrowProjectionTest {
    private static Ast.Program checked(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(program);
        return program;
    }

    private static String run(String source) throws Exception {
        checked(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source polyglot = Source.newBuilder(OresLanguage.ID, source, "arrow-arms.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(polyglot);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void doMatchTaggedRecordProjectionRunsAndMarksDiscardingForm() throws Exception {
        String source = """
                pub routine main(): void {
                  val Option<{v: int, unused: string}> item =
                      Some(struct{v: int, unused: string}{v: 7, unused: "hidden"});
                  do match item over
                    on Some as {v} -> {
                      stdio.stdout.write(v);
                    }
                    on None -> {
                      stdio.stdout.write("none");
                    }
                  end
                  stdio.stdout.write(":end");
                  return;
                }
                """;
        Ast.Program program = checked(source);
        var fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(((Ast.MatchStmt) fn.body().get(1)).procedural());
        assertEquals("7:end", run(source));
    }

    @Test
    void doSelectLetRecordProjectionBindsAndRebindsWinningArmOnly() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<{x: int, y: string}> messages =
                      Channel.new<{x: int, y: string}>(1);
                  writech messages, struct{x: int, y: string}{x: 5, y: "ok"};
                  do select {
                    when readch messages: let {x,y} -> {
                      x = x + 2;
                      stdio.stdout.write(x);
                      stdio.stdout.write(y);
                    }
                  }
                  return;
                }
                """;
        assertEquals("7ok", run(source));
    }

    @Test
    void rejectsInvalidMatchRecordPatternFieldsAndDuplicateNames() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> checked("""
                        fnc test(Option<{v: int}> item): void {
                          do match item over
                            on Some as {missing} -> { return; }
                            on None -> { return; }
                          end
                          return;
                        }
                        """));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc test(Option<{v: int}> item): void {
                          do match item over
                            on Some as {v,v} -> { return; }
                            on None -> { return; }
                          end
                        }
                        """));
        assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());

        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc test(Option<{v: int}> item): void {
                          do match item over
                            on Some as {} -> { return; }
                            on None -> { return; }
                          end
                        }
                        """));
        assertTrue(absent.getMessage().contains("at least one"), absent.getMessage());
    }

    @Test
    void implicitRecordProjectionRequiresArrowAndLegacyBracesStayBodies() {
        Ast.Program parsed = checked("""
                fnc test(Channel<{x: int}> data): void {
                  select {
                    when readch data: {x} -> {
                      stdio.println(x);
                    }
                  }
                  select {
                    when readch data: {
                      stdio.println("legacy body");
                    }
                  }
                  return;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt selected = (Ast.SelectStmt) fn.body().getFirst();
        assertEquals(Ast.DestructureKind.OBJECT,
                selected.arms().getFirst().destructureKind());
        Ast.SelectStmt legacy = (Ast.SelectStmt) fn.body().get(1);
        assertNull(legacy.arms().getFirst().destructureKind());
        assertNull(legacy.arms().getFirst().bindingName());
    }

    @Test
    void requiresMatchSlimArrowAndRejectsUnsupportedConstructorWithRecordProjection() {
        IllegalArgumentException arrow = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc test(Option<{v: int}> item): void {
                          do match item over
                            on Some as {v} { return; }
                            on None -> { return; }
                          end
                        }
                        """));
        assertTrue(arrow.getMessage().contains("slim arrow"), arrow.getMessage());

        // A user-defined enum/variant name is parsed but not yet a supported
        // compiled constructor. Never silently treat it as a class or shape.
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> checked("""
                        fnc test(Option<{v: int}> item): void {
                          do match item over
                            on Foo as {v} -> { return; }
                            on None -> { return; }
                          end
                          return;
                        }
                        """));
        assertTrue(unknown.getMessage().contains("constructor Foo"), unknown.getMessage());
    }

    @Test
    void typedSelectProjectionRequiresSlimArrowToSeparatePatternAndBody() {
        IllegalArgumentException noArrow = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc read(Channel<{x: int, y: int}> values): void {
                          do select {
                            when readch values: let {x,y} {
                              stdio.println(x);
                            }
                          }
                          return;
                        }
                        """));
        assertTrue(noArrow.getMessage().contains("require '->'"), noArrow.getMessage());

        IllegalArgumentException typed = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc read(Channel<int> values): void {
                          select {
                            when readch values: int n {
                              stdio.println(n);
                            }
                          }
                          return;
                        }
                        """));
        assertTrue(typed.getMessage().contains("require '->'"), typed.getMessage());
    }

    @Test
    void arrowDisambiguatesSelectRecordPatternFromBracedArmBody() {
        Ast.Program program = checked("""
                fnc test(Channel<{x: int, y: string}> messages): void {
                  do select {
                    when readch messages: let {x,y} -> {
                      stdio.println(x);
                      stdio.println(y);
                    }
                    default: -> {
                      stdio.println("none");
                    }
                  }
                  return;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt select = (Ast.SelectStmt) fn.body().getFirst();
        assertTrue(select.explicitDo());
        Ast.SelectArm read = select.arms().getFirst();
        assertEquals(Ast.DestructureKind.OBJECT, read.destructureKind());
        assertEquals(Ast.BindingKind.LET, read.bindingKind());
        assertEquals(2, read.destructureBindings().size());
    }
    @Test
    void genericDefaultsAndArrowSelectProjectionCoexistAfterMainlineIntegration()
            throws Exception {
        String source = """
                fnc take<A, B=int>(A ignored, Channel<{v: int}> ch): int {
                  do select {
                    when readch ch: const {v} -> {
                      return v;
                    }
                  }
                  return 0;
                }
                pub routine main(): void {
                  val Channel<{v: int}> ch = Channel.new<{v: int}>(1);
                  writech ch, struct{v: int}{v: 44};
                  stdio.stdout.write(take<int>(7, ch));
                  return;
                }
                """;
        assertEquals("44", run(source));
    }

    @Test
    void nestedGenericInferenceHoleDoesNotCrossArrowSelectBoundary() {
        String source = """
                fnc packed<T>(T value): int { return 7; }
                fnc invalid(Channel<{v: int}> ch): int {
                  do select {
                    when readch ch: const {v} -> {
                      return packed<Array<_>>(v);
                    }
                  }
                  return 0;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checked(source));
        assertTrue(failure.getMessage().contains("nested inference hole"),
                failure.getMessage());
    }

}

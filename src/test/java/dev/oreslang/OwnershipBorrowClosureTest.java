package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OwnershipBorrowClosureTest {
    @Test
    void parametersAreImmutableUnlessMutIsExplicit() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  let String foo = "before";
                end

                fnc change(Bar b) => void {
                  b.foo = "after";
                  return;
                }
                """));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  let String foo = "before";
                end

                fnc change(Bar mut b) => void {
                  b.foo = "after";
                  return;
                }
                """));
    }

    @Test
    void mutableParameterExecutesFieldMutation() throws Exception {
        assertEquals("after", run("""
                define class Bar
                  let String foo = "before";
                end

                fnc change(Bar mut b) => void {
                  b.foo = "after";
                  stdio.stdout.write(b.foo);
                  return;
                }

                pub routine main() => void {
                  val b = new Bar();
                  change(b);
                  return;
                }
                """));
    }

    @Test
    void immutableFieldsRemainImmutableEvenThroughMutableOwners() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  val String foo = "before";
                end

                fnc change(Bar mut b) => void {
                  b.foo = "after";
                  return;
                }
                """));
    }

    @Test
    void mutableBorrowAllowsTemporaryExclusiveMutation() throws Exception {
        assertEquals("after", run("""
                define class Bar
                  let String foo = "before";
                end

                fnc change(&mut Bar b) => void {
                  b.foo = "after";
                  return;
                }

                pub routine main() => void {
                  let b = new Bar();
                  change(&mut b);
                  stdio.stdout.write(b.foo);
                  return;
                }
                """));
    }

    @Test
    void immutableBorrowCannotMutate() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  let String foo = "before";
                end

                fnc change(&Bar b) => void {
                  b.foo = "after";
                  return;
                }
                """));
    }

    @Test
    void sharedAndExclusiveBorrowsCannotOverlap() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  let String foo = "before";
                end

                pub routine main() => void {
                  let b = new Bar();
                  val shared = &b;
                  val exclusive = &mut b;
                  stdio.println(shared.foo);
                  return;
                }
                """));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  val String foo = "ok";
                end

                fnc read(&Bar b) => void { stdio.println(b.foo); return; }

                pub routine main() => void {
                  let b = new Bar();
                  read(&b);
                  read(&b);
                  return;
                }
                """));
    }

    @Test
    void ownedArgumentsMoveAndCopyArgumentsDoNot() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  val String foo = "x";
                end

                fnc consume(Bar b) => void { return; }

                pub routine main() => void {
                  val b = new Bar();
                  consume(b);
                  stdio.println(b.foo);
                  return;
                }
                """));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc consume(int x) => void { return; }

                pub routine main() => void {
                  val x = 4;
                  consume(x);
                  stdio.println(x);
                  return;
                }
                """));
    }

    @Test
    void closuresOwnLexicalCapturesAndCanCarryMutableState() throws Exception {
        assertEquals("12", run("""
                fnc makeCounter() => (() -> int) {
                  let count = 0;
                  return || -> {
                    count = count + 1;
                    return count;
                  };
                }

                pub routine main() => void {
                  val counter = makeCounter();
                  stdio.stdout.write(counter());
                  stdio.stdout.write(counter());
                  return;
                }
                """));
    }

    @Test
    void movedMutableCaptureCannotBeUsedOutsideClosure() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main() => void {
                  let count = 0;
                  val f = || -> {
                    count = count + 1;
                    return count;
                  };
                  count = 9;
                  stdio.println(f());
                  return;
                }
                """));
    }

    @Test
    void closuresCannotCaptureBorrowedReferences() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  val String foo = "x";
                end

                pub routine main() => void {
                  let b = new Bar();
                  val borrowed = &b;
                  val f = || -> {
                    stdio.println(borrowed.foo);
                    return;
                  };
                  f();
                  return;
                }
                """));
    }

    @Test
    void borrowsCannotEscapeReturnOrHeapStorage() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  val String foo = "x";
                end

                fnc bad(&Bar b) => &Bar {
                  return b;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Bar
                  val String foo = "x";
                end

                define class Holder
                  val &Bar value;
                end
                """));
    }

    @Test
    void mutableMethodsRequireMutableReceivers() throws Exception {
        assertEquals("1", run("""
                define class Counter
                  let int value = 0;

                  mut inc() => void {
                    self.value = self.value + 1;
                    return;
                  }
                end

                pub routine main() => void {
                  let counter = new Counter();
                  counter.inc();
                  stdio.stdout.write(counter.value);
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Counter
                  let int value = 0;
                  mut inc() => void {
                    self.value = self.value + 1;
                    return;
                  }
                end

                pub routine main() => void {
                  val counter = new Counter();
                  counter.inc();
                  return;
                }
                """));
    }

    @Test
    void boundMethodValuesOwnTheirReceiver() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Box
                  val int value = 7;
                  get() => int { return self.value; }
                end

                pub routine main() => void {
                  val box = new Box();
                  val callback = box.get;
                  stdio.println(box.value);
                  stdio.println(callback());
                  return;
                }
                """));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class Box
                  val int value = 7;
                  get() => int { return self.value; }
                end

                pub routine main() => void {
                  val box = new Box();
                  stdio.println(box.get());
                  stdio.println(box.get());
                  return;
                }
                """));
    }

    @Test
    void parserMarksMutabilityAndBorrowTypesExplicitly() {
        Ast.Program program = Parser.parse("""
                fnc f(&mut String ref, String mut owned) => void { return; }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("MutRef", fn.parameters().getFirst().type().name());
        assertFalse(fn.parameters().getFirst().mutable());
        assertEquals("String", fn.parameters().get(1).type().name());
        assertTrue(fn.parameters().get(1).mutable());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "ownership.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

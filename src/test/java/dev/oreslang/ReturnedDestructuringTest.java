package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReturnedDestructuringTest {

    @Test
    void returnedArraysSupportConstValLetAndBindingPropagation() throws Exception {
        String output = run("""
                fnc getFoo() => Array<int> {
                  return arr[3, 4, 5];
                }

                pub fnc main() => void {
                  [const v, val c, let n] = getFoo();
                  n = n + 1;
                  stdio.stdout.write(v);
                  stdio.stdout.write(c);
                  stdio.stdout.write(n);
                  return;
                }
                """);

        assertEquals("346", output);
    }

    @Test
    void returnedStructsAndObjectsUseNamedObjectPatterns() throws Exception {
        String output = run("""
                struct Result {
                  pub x: int
                  pub bar: String
                }

                define class Payload as
                  pub val int x;
                  pub val String bar;
                end

                fnc getBar() => Result {
                  return Result { x = 7, bar = "struct" };
                }

                fnc getObject() => Payload {
                  return new Payload(8, "object");
                }

                pub fnc main() => void {
                  {const x, const bar} = getBar();
                  stdio.stdout.write(x);
                  stdio.stdout.write(bar);

                  {const x2, const bar2} = getObject();
                  stdio.stdout.write(x2);
                  stdio.stdout.write(bar2);
                  return;
                }
                """);

        assertEquals("7struct8object", output);
    }

    @Test
    void structuralObjectValuesDestructureByKey() throws Exception {
        String output = run("""
                pub fnc main() => void {
                  {const x, const bar} = obj{x: 5, bar: "ok"};
                  stdio.stdout.write(x);
                  stdio.stdout.write(bar);
                  return;
                }
                """);

        assertEquals("5ok", output);
    }

    @Test
    void mutDestructureMakesOwnedValuesMutableWithoutMakingValRebindable() throws Exception {
        String output = run("""
                fnc getLists() => Array<Array<int>> {
                  return arr[arr[1, 2], arr[3, 4]];
                }

                pub fnc main() => void {
                  [val mut left, const right] = getLists();
                  left[0] = 9;
                  stdio.stdout.write(left[0]);
                  stdio.stdout.write(right[0]);
                  return;
                }
                """);

        assertEquals("93", output);

        IllegalArgumentException rebind = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc getLists() => Array<Array<int>> {
                          return arr[arr[1], arr[2]];
                        }

                        fnc bad() => void {
                          [val mut left, const right] = getLists();
                          left = arr[7];
                          return;
                        }
                        """)));
        assertTrue(rebind.getMessage().contains("cannot reassign val"));
    }

    @Test
    void bareAndPrefixedMutWorkForMutableStructReceivers() throws Exception {
        String output = run("""
                struct Counter {
                  pub let int count = 0;

                  pub bump(self &mut self)() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }
                }

                fnc counters() => Array<Counter> {
                  return arr[Counter {}, Counter {}];
                }

                pub fnc main() => void {
                  [mut first, const second] = counters();
                  stdio.stdout.write(first.bump());
                  stdio.stdout.write(second.count);

                  mut [third, fourth] = counters();
                  stdio.stdout.write(third.bump());
                  stdio.stdout.write(fourth.bump());
                  return;
                }
                """);

        assertEquals("1011", output);

        IllegalArgumentException immutable = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Counter {
                          pub let int count = 0;

                          pub bump(self &mut self)() => int {
                            self.count = self.count + 1;
                            return self.count;
                          }
                        }

                        fnc counters() => Array<Counter> {
                          return arr[Counter {}, Counter {}];
                        }

                        fnc bad() => void {
                          [mut first, const second] = counters();
                          second.bump();
                          return;
                        }
                        """)));
        assertTrue(immutable.getMessage().contains("immutable"));
    }

    @Test
    void objectDestructureCanExposeMutableOwnedMembers() throws Exception {
        String output = run("""
                struct Bag {
                  pub values: Array<int>
                  pub name: String
                }

                fnc getBag() => Bag {
                  return Bag { values = arr[1, 2], name = "bag" };
                }

                pub fnc main() => void {
                  {val mut values, const name} = getBag();
                  values[1] = 8;
                  stdio.stdout.write(values[1]);
                  stdio.stdout.write(name);
                  return;
                }
                """);

        assertEquals("8bag", output);
    }

    @Test
    void letDestructureIsRebindableAndAlsoProvidesMutableOwnership() throws Exception {
        String output = run("""
                struct Counter {
                  pub let int count = 0;

                  pub bump(self &mut self)() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }
                }

                fnc counters() => Array<Counter> {
                  return arr[Counter {}, Counter {}];
                }

                pub fnc main() => void {
                  [let first, const second] = counters();
                  stdio.stdout.write(first.bump());
                  first = Counter {};
                  stdio.stdout.write(first.bump());
                  stdio.stdout.write(second.count);
                  return;
                }
                """);

        assertEquals("110", output);
    }

    @Test
    void valWithoutMutCannotUseMutableStructReceiver() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Counter {
                          pub let int count = 0;

                          pub bump(self &mut self)() => int {
                            self.count = self.count + 1;
                            return self.count;
                          }
                        }

                        fnc counters() => Array<Counter> {
                          return arr[Counter {}, Counter {}];
                        }

                        fnc bad() => void {
                          [val first, let second] = counters();
                          first.bump();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable"));
    }

    @Test
    void constAndMutCannotDescribeTheSameDestructuredBinding() {
        for (String program : new String[] {
                """
                fnc bad() => void {
                  [const mut value] = arr[1];
                  return;
                }
                """,
                """
                fnc bad() => void {
                  const [mut value] = arr[1];
                  return;
                }
                """,
                """
                fnc bad() => void {
                  const mut [value] = arr[1];
                  return;
                }
                """,
                """
                fnc bad() => void {
                  {const mut x} = obj{x: 1};
                  return;
                }
                """
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(program));
            assertTrue(error.getMessage().contains("both const and mut"));
        }
    }

    @Test
    void objectPatternsRejectMissingMembersAndTuplePatternsKeepExactArity() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Result {
                          pub x: int
                        }

                        fnc result() => Result {
                          return Result { x = 1 };
                        }

                        fnc bad() => void {
                          {const missing} = result();
                          return;
                        }
                        """)));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          [const a, const b, const c] = (1, 2);
                          return;
                        }
                        """)));
    }

    @Test
    void sequenceDiscardDoesNotCreateABindingAndObjectDiscardIsRejected() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc result() => Array<int> {
                  return arr[1, 2, 3];
                }

                fnc ok() => void {
                  [const first, _, const last] = result();
                  stdio.println(first);
                  stdio.println(last);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad() => void {
                  {const x, _} = obj{x: 1, y: 2};
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "returned-destructuring.ores")
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

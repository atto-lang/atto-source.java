package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeProxyTest {

    @Test
    void parserAcceptsCommandAndParenthesizedProxyForms() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Box as
                  pub let int value = 1;
                end

                fnc ok() => void {
                  let Box a = new Box();
                  val pa = rt proxy a;
                  let Box b = new Box();
                  val pb = rt proxy(b);
                  stdio.println(pa.value);
                  stdio.println(pb.value);
                  return;
                }
                """));
    }

    @Test
    void proxyConsumesRawOwnerAndCannotBeTakenSharedOrPointedAt() {
        IllegalArgumentException moved = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end
                        fnc bad() => void {
                          let Box box = new Box();
                          val proxy = rt proxy box;
                          stdio.println(box.value);
                          return;
                        }
                        """)));
        assertTrue(moved.getMessage().contains("moved value 'box'"), moved.getMessage());

        for (String expression : java.util.List.of(
                "rt take proxy",
                "rt share proxy",
                "rt ptr proxy")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define class Box as
                              pub let int value = 1;
                            end
                            fnc bad() => void {
                              let Box box = new Box();
                              val proxy = rt proxy box;
                              val escaped = %s;
                              stdio.println(proxy.value);
                              return;
                            }
                            """.formatted(expression))));
            assertTrue(error.getMessage().toLowerCase().contains("proxy"), error.getMessage());
        }
    }

    @Test
    void proxyRejectsPrimitiveBorrowAndSharedOwners() {
        IllegalArgumentException scalar = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad() => void {
                          let int value = 1;
                          val proxy = rt proxy value;
                          return;
                        }
                        """)));
        assertTrue(scalar.getMessage().contains("class/struct"), scalar.getMessage());

        IllegalArgumentException borrowed = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end
                        fnc bad() => void {
                          let Box box = new Box();
                          val view = rt borrow box;
                          val proxy = rt proxy view;
                          return;
                        }
                        """)));
        assertTrue(borrowed.getMessage().toLowerCase().contains("borrow"), borrowed.getMessage());

        IllegalArgumentException shared = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end
                        fnc bad() => void {
                          let Box box = new Box();
                          val shared = rt share box;
                          val proxy = rt proxy shared;
                          return;
                        }
                        """)));
        assertTrue(shared.getMessage().toLowerCase().contains("shared"), shared.getMessage());
    }

    @Test
    void classProxyMediatesReadAndMutableReceiverCalls() throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 0;

                  pub read() => int {
                    return self.value;
                  }

                  pub bump(mut self)() => int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                end

                pub routine main() => void {
                  let Counter counter = new Counter();
                  val proxy = rt proxy counter;
                  stdio.stdout.write(proxy.read());
                  stdio.stdout.write(":");
                  stdio.stdout.write(proxy.bump());
                  stdio.stdout.write(":");
                  stdio.stdout.write(proxy.value);
                  return;
                }
                """);

        assertEquals("0:1:1", output);
    }

    @Test
    void nestedClassViewsReuseProxyMediation() throws Exception {
        String output = run("""
                define class Inner as
                  pub let int value = 1;

                  pub bump(mut self)() => int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                end

                define class Outer as
                  pub val Inner inner = new Inner();
                end

                pub routine main() => void {
                  let Outer outer = new Outer();
                  val proxy = rt proxy outer;
                  stdio.stdout.write(proxy.inner.bump());
                  stdio.stdout.write(":");
                  stdio.stdout.write(proxy.inner.value);
                  return;
                }
                """);

        assertEquals("2:2", output);
    }

    @Test
    void structuralRecordProxyUsesSynchronizedFieldAccess() throws Exception {
        String output = run("""
                pub routine main() => void {
                  let state = obj { count: 1, label: "x" };
                  val proxy = rt proxy state;
                  proxy.count = 7;
                  stdio.stdout.write(proxy.label);
                  stdio.stdout.write(":");
                  stdio.stdout.write(proxy.count);
                  return;
                }
                """);

        assertEquals("x:7", output);
    }

    @Test
    void proxyFailsClosedOnRawMutableAggregateEscape() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val Array<int> values = [1, 2, 3];
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val proxy = rt proxy box;
                          val values = proxy.values;
                          stdio.println(values[0]);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot expose unsynchronized mutable type"),
                error.getMessage());
    }

    @Test
    void proxyRejectsConsumingAndAsyncMethods() {
        IllegalArgumentException consuming = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 1;
                          pub consume(take self)() => int {
                            return self.value;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val proxy = rt proxy box;
                          stdio.println(proxy.consume());
                          return;
                        }
                        """)));
        assertTrue(consuming.getMessage().contains("proxy"), consuming.getMessage());

        IllegalArgumentException async = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 1;
                          pub async read() => int {
                            return self.value;
                          }
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val proxy = rt proxy box;
                          val result = proxy.read();
                          return;
                        }
                        """)));
        assertTrue(async.getMessage().contains("async method"), async.getMessage());
    }

    @Test
    void copyThroughProxyProducesIndependentRawValueWhenCopyContractExists() throws Exception {
        String output = run("""
                define class Box as
                  pub let int value = 1;

                  pub copy() => self {
                    let Box other = new Box();
                    other.value = self.value;
                    return other;
                  }

                  pub bump(mut self)() => int {
                    self.value = self.value + 1;
                    return self.value;
                  }
                end

                pub routine main() => void {
                  let Box box = new Box();
                  val proxy = rt proxy box;
                  let Box copied = rt copy proxy;
                  proxy.bump();
                  stdio.stdout.write(copied.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(proxy.value);
                  return;
                }
                """);

        assertEquals("1:2", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rt-proxy.ores")
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

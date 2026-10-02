package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MethodOverloadTest {
    @Test
    void instanceMethodsDispatchOnlyByNameAndArityAtRuntime() throws Exception {
        String output = run("""
                define module m
                  define class C
                    pub pick() => int { return 1; }
                    pub pick(int value) => int { return value; }
                    pub pick(int left, int right) => int { return left + right; }
                  end
                end

                pub routine main() => void {
                  val c = new m.C();
                  stdio.stdout.write(c.pick());
                  stdio.stdout.write(c.pick(4));
                  stdio.stdout.write(c.pick(2, 3));
                }
                """);

        assertEquals("145", output);
    }

    @Test
    void parameterTypesDoNotCreateDistinctOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C
                            pub find(int value) => int { return value; }
                            pub find(String value) => int { return 1; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
        assertTrue(error.getMessage().contains("name + arity only"));
    }

    @Test
    void returnTypesDoNotCreateDistinctOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C
                            pub find(int value) => int { return value; }
                            pub find(int value) => String { return "same slot"; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
        assertTrue(error.getMessage().contains("parameter types and return types do not participate"));
    }

    @Test
    void genericParameterNamesAndTypesDoNotCreateDistinctOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C
                            pub identity<T>(T value) => T { return value; }
                            pub identity<U>(U value) => U { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
    }

    @Test
    void explicitSelfReceiverDoesNotCountTowardOverloadArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class C
                    pub read(self C)() => int { return 1; }
                    pub read(self C)(int value) => int { return value; }
                  end
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C
                            pub read() => int { return 1; }
                            pub read(self C)() => int { return 2; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 0"));
    }

    @Test
    void staticClassFunctionsUseTheSameArityOnlyRule() throws Exception {
        String output = run("""
                define module m
                  define class C
                    pub static fnc pick() => int { return 2; }
                    pub static fnc pick(int value) => int { return value; }
                  end
                end

                pub routine main() => void {
                  stdio.stdout.write(m.C.pick());
                  stdio.stdout.write(m.C.pick(7));
                }
                """);

        assertEquals("27", output);

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C
                            pub static fnc find(int value) => int { return value; }
                            pub static fnc find(String value) => int { return 1; }
                          end
                        end
                        """)));
    }

    @Test
    void inheritedOverloadsRemainAvailableByArity() throws Exception {
        String output = run("""
                define module m
                  define class Base
                    pub pick() => int { return 1; }
                    pub pick(int value) => int { return value; }
                  end

                  define class Child extends Base
                    pub pick(int left, int right) => int { return left + right; }
                  end
                end

                pub routine main() => void {
                  val c = new m.Child();
                  stdio.stdout.write(c.pick());
                  stdio.stdout.write(c.pick(4));
                  stdio.stdout.write(c.pick(2, 3));
                }
                """);

        assertEquals("145", output);
    }

    @Test
    void changingParameterTypeInAChildOccupiesTheSameOverrideSlot() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base
                            pub find(int value) => int { return value; }
                          end

                          define class Child extends Base
                            pub find(String value) => int { return 1; }
                          end
                        end
                        """)));
    }

    @Test
    void interfacesAndImplementationsUseArityOnlyMethodSlots() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Lookup {
                  fnc find() => int;
                  fnc find(int value) => int;
                }

                define module m
                  define class C implements Lookup
                    pub find() => int { return 0; }
                    pub find(int value) => int { return value; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Lookup {
                  fnc find(int value) => int;
                  fnc find(String value) => int;
                }
                """)));
    }

    @Test
    void overloadedMethodValueRequiresCallArityForSelection() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C
                            pub pick() => int { return 1; }
                            pub pick(int value) => int { return value; }
                          end
                        end

                        fnc hold() => void {
                          val c = new m.C();
                          val callback = c.pick;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("must be called so arity can select the overload"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "method-overload.ores")
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

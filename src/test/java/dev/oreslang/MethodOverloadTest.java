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
    void unrelatedParentsWithSameArityRequireExplicitChildOverrideEvenWhenTypesMatch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Left
                            pub pick(int value) => int { return value; }
                          end

                          define class Right
                            pub pick(int value) => int { return value; }
                          end

                          define class Child extends Left, Right
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method 'Child.pick' with arity 1"));
        assertTrue(error.getMessage().contains("m.Left"));
        assertTrue(error.getMessage().contains("m.Right"));
        assertTrue(error.getMessage().contains("argument and return types never select an overload"));
    }

    @Test
    void explicitChildOverrideResolvesSameArityMultipleInheritance() throws Exception {
        String output = run("""
                define module m
                  define class Left
                    pub pick(int value) => int { return value + 10; }
                  end

                  define class Right
                    pub pick(int value) => int { return value + 20; }
                  end

                  define class Child extends Left, Right
                    pub pick(int value) => int { return value + 30; }
                  end
                end

                pub routine main() => void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick(2));
                }
                """);

        assertEquals("32", output);
    }

    @Test
    void trueDiamondInheritanceOfTheSameDeclarationIsNotAmbiguous() throws Exception {
        String output = run("""
                define module m
                  define class Base
                    pub pick(int value) => int { return value + 1; }
                  end

                  define class Left extends Base
                  end

                  define class Right extends Base
                  end

                  define class Diamond extends Left, Right
                  end
                end

                pub routine main() => void {
                  val value = new m.Diamond();
                  stdio.stdout.write(value.pick(4));
                }
                """);

        assertEquals("5", output);
    }

    @Test
    void unrelatedParentsCanContributeDifferentAritiesWithoutConflict() throws Exception {
        String output = run("""
                define module m
                  define class Left
                    pub pick() => int { return 1; }
                  end

                  define class Right
                    pub pick(int value) => int { return value; }
                  end

                  define class Child extends Left, Right
                  end
                end

                pub routine main() => void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick());
                  stdio.stdout.write(child.pick(7));
                }
                """);

        assertEquals("17", output);
    }

    @Test
    void inheritedStaticFunctionsAlsoRequireExplicitResolutionForSameArity() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Left
                            pub static fnc pick(int value) => int { return value; }
                          end

                          define class Right
                            pub static fnc pick(int value) => int { return value; }
                          end

                          define class Child extends Left, Right
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited static function 'Child.pick' with arity 1"));
    }

    @Test
    void parentOrderNeverChoosesAWinningSameArityMethod() {
        String leftFirst = """
                define module m
                  define class Left
                    pub pick(int value) => int { return value; }
                  end
                  define class Right
                    pub pick(int value) => int { return value; }
                  end
                  define class Child extends Left, Right
                  end
                end
                """;
        String rightFirst = """
                define module m
                  define class Left
                    pub pick(int value) => int { return value; }
                  end
                  define class Right
                    pub pick(int value) => int { return value; }
                  end
                  define class Child extends Right, Left
                  end
                end
                """;

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(leftFirst)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(rightFirst)));
    }

    @Test
    void asyncModifierDoesNotCreateASecondSameArityOverloadSlot() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class C
                            pub find(int value) => int { return value; }
                            pub async find(int value) => int { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("already has arity 1"));
    }

    @Test
    void staticFunctionCannotCaptureEnclosingClassGenericAsAnImplicitDispatchType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Box<T>
                            pub static fnc bad(T value) => T { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot use enclosing class generic 'T'"));
        assertTrue(error.getMessage().contains("declare a static-function generic instead"));
    }

    @Test
    void staticFunctionMayUseItsOwnGenericWithoutChangingArityIdentity() throws Exception {
        String output = run("""
                define module m
                  define class Box<T>
                    pub static fnc identity<U>(U value) => U { return value; }
                  end
                end

                pub routine main() => void {
                  stdio.stdout.write(m.Box.identity(6));
                }
                """);

        assertEquals("6", output);
    }

    @Test
    void staticAndInstanceNamespacesRemainDistinct() throws Exception {
        String output = run("""
                define module m
                  define class C
                    pub pick(int value) => int { return value + 1; }
                    pub static fnc pick(int value) => int { return value + 2; }
                  end
                end

                pub routine main() => void {
                  val c = new m.C();
                  stdio.stdout.write(c.pick(3));
                  stdio.stdout.write(m.C.pick(3));
                }
                """);

        assertEquals("45", output);
    }

    @Test
    void inheritedGenericMethodUsesConcreteParentBindingAfterAritySelection() throws Exception {
        String output = run("""
                define module m
                  define class Base<T>
                    pub pick(T value) => T { return value; }
                  end

                  define class Child extends Base<int>
                  end
                end

                pub routine main() => void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick(7));
                }
                """);

        assertEquals("7", output);

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base<T>
                            pub pick(T value) => T { return value; }
                          end

                          define class Child extends Base<int>
                          end
                        end

                        fnc bad(m.Child child) => void {
                          child.pick("wrong");
                          return;
                        }
                        """)));
    }

    @Test
    void concreteGenericParentContractChecksTheSelectedOverrideSlot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class Base<T>
                    pub pick(T value) => T { return value; }
                  end

                  define class Good extends Base<int>
                    pub pick(int value) => int { return value; }
                  end
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base<T>
                            pub pick(T value) => T { return value; }
                          end

                          define class Bad extends Base<int>
                            pub pick(String value) => String { return value; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("conflicting member 'pick$arity1'"));
    }

    @Test
    void sameGenericDiamondViewOfOneDeclarationIsSafe() throws Exception {
        String output = run("""
                define module m
                  define class Base<T>
                    pub pick(T value) => T { return value; }
                  end

                  define class Left extends Base<int>
                  end

                  define class Right extends Base<int>
                  end

                  define class Child extends Left, Right
                  end
                end

                pub routine main() => void {
                  val child = new m.Child();
                  stdio.stdout.write(child.pick(9));
                }
                """);

        assertEquals("9", output);
    }

    @Test
    void conflictingGenericDiamondViewsRequireExplicitChildResolution() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base<T>
                            pub pick(T value) => T { return value; }
                          end

                          define class Left extends Base<int>
                          end

                          define class Right extends Base<String>
                          end

                          define class Child extends Left, Right
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method 'Child.pick' with arity 1"));
        assertTrue(error.getMessage().contains("m.Base"));
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
    void genericInterfaceMethodSlotUsesConcreteTypeArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Lookup<T> {
                  fnc find(T value) => T;
                }

                define module m
                  define class C implements Lookup<int>
                    pub find(int value) => int { return value; }
                  end
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub interface Lookup<T> {
                  fnc find(T value) => T;
                }

                define module m
                  define class C implements Lookup<int>
                    pub find(String value) => String { return value; }
                  end
                end
                """)));
    }

    @Test
    void sameGenericInterfaceDiamondViewIsCompatible() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Base<T> {
                  fnc pick(T value) => T;
                }

                pub interface Left extends Base<int> {
                }

                pub interface Right extends Base<int> {
                }

                pub interface Combined extends Left, Right {
                }

                define module m
                  define class C implements Combined
                    pub pick(int value) => int { return value; }
                  end
                end
                """)));
    }

    @Test
    void conflictingGenericInterfaceDiamondCannotBecomeTypeBasedOverloads() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub interface Base<T> {
                          fnc pick(T value) => T;
                        }

                        pub interface Left extends Base<int> {
                        }

                        pub interface Right extends Base<String> {
                        }

                        pub interface Combined extends Left, Right {
                        }
                        """)));

        assertTrue(error.getMessage().contains("conflicting member"));
        assertTrue(error.getMessage().contains("pick$arity1"));
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

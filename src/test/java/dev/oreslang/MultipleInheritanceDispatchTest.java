package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MultipleInheritanceDispatchTest {
    @Test
    void distinctParentImplementationsRequireExplicitOverride() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class A as
                            pub foo() => int { return 1; }
                          end

                          define class B as
                            pub foo() => int { return 2; }
                          end

                          define class Bad extends A, B as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method 'foo'"));
        assertTrue(error.getMessage().contains("super.Parent.foo"));
    }

    @Test
    void diamondPathsRequireExplicitOverrideEvenWhenTheyReachSameBaseMethod() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class Root as
                            pub foo() => int { return 1; }
                          end

                          define class Left extends Root as
                          end

                          define class Right extends Root as
                          end

                          define class Diamond extends Left, Right as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited method 'foo'"));
        assertTrue(error.getMessage().contains("Left"));
        assertTrue(error.getMessage().contains("Right"));
    }

    @Test
    void explicitOverrideCanDelegateToEitherNamedParent() throws Exception {
        String program = """
                define module model as
                  define class A as
                    pub foo() => int { return 1; }
                  end

                  define class B as
                    pub foo() => int { return 2; }
                  end

                  define class Good extends A, B as
                    pub from_a() => int {
                      return super.A.foo();
                    }

                    pub from_b() => int {
                      return super.B.foo();
                    }

                    pub foo() => int {
                      return super.B.foo();
                    }
                  end
                end

                define module app as
                  pub fnc main() => void {
                    val value = new Good();
                    stdio.println(value.from_a());
                    stdio.println(value.from_b());
                    stdio.println(value.foo());
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "multiple-inheritance.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("1"));
        assertTrue(text.contains("2"));
    }

    @Test
    void superCanNameOnlyADirectParent() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class Root as
                            pub foo() => int { return 1; }
                          end

                          define class Middle extends Root as
                          end

                          define class Child extends Middle as
                            pub foo() => int {
                              return super.Root.foo();
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("super.Root"));
        assertTrue(error.getMessage().contains("direct parent"));
    }

    @Test
    void inheritedStateNeverUsesLeftmostParentWins() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define class A as
                            private val int state = 1;
                          end

                          define class B as
                            private val int state = 2;
                          end

                          define class Bad extends A, B as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous inherited state field 'state'"));
    }

    @Test
    void multipleInheritanceRemainsLegalWhenMembersDoNotCollide() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define class A as
                    pub a() => int { return 1; }
                  end

                  define class B as
                    pub b() => int { return 2; }
                  end

                  define class Combined extends A, B as
                  end
                end
                """)));
    }
}

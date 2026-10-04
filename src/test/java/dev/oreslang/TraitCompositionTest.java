package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TraitCompositionTest {
    @Test
    void interfacesAreStorageFreeContracts() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define interface Bad as
                          val int state;
                        end
                        """));

        assertTrue(error.getMessage().contains("storage-free contracts"));
    }

    @Test
    void multipleConcreteTraitMethodsRequireExplicitClassResolution() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait A as
                            pub value() => int { return 1; }
                          end

                          define trait B as
                            pub value() => int { return 2; }
                          end

                          define class Bad with A, B as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("ambiguous trait method"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait A as
                    pub value() => int { return 1; }
                  end

                  define trait B as
                    pub value() => int { return 2; }
                  end

                  define class Good with A, B as
                    pub value() => int { return 3; }
                  end
                end
                """)));
    }

    @Test
    void traitRequirementsMustBeImplementedByConcreteClasses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Loads as
                            pub abstract load() => int;

                            pub read() => int {
                              return self.load();
                            }
                          end

                          define class Bad with Loads as
                          end
                        end
                        """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait Loads as
                    pub abstract load() => int;

                    pub read() => int {
                      return self.load();
                    }
                  end

                  define class Good with Loads as
                    pub load() => int { return 42; }
                  end
                end
                """)));
    }

    @Test
    void traitsCannotBeInstantiatedDirectly() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                            private val int count = 0;
                          end

                          pub fnc make() => void {
                            val counter = new Counter();
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("cannot be instantiated"));
        assertTrue(error.getMessage().contains("with"));
    }

    @Test
    void privateTraitMethodsRemainLexicalToTheTrait() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait Secret as
                    private helper() => int {
                      return 7;
                    }

                    pub read() => int {
                      return self.helper();
                    }
                  end

                  define class Good with Secret as
                  end
                end
                """)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Secret as
                            private helper() => int {
                              return 7;
                            }

                            pub read() => int {
                              return self.helper();
                            }
                          end

                          define class Bad with Secret as
                            pub leak() => int {
                              return self.helper();
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("trait-private method"));
        assertTrue(error.getMessage().contains("Secret.helper"));
    }

    @Test
    void hostClassesCannotOverridePrivateTraitHelpers() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Secret as
                            private helper() => int {
                              return 7;
                            }

                            pub read() => int {
                              return self.helper();
                            }
                          end

                          define class Bad with Secret as
                            pub helper() => int {
                              return 99;
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("trait-private method"));
        assertTrue(error.getMessage().contains("cannot be overridden"));
    }

    @Test
    void privateAbstractTraitRequirementsAreRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Impossible as
                            private abstract load() => int;
                          end

                          define abstract class Host with Impossible as
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("private abstract method"));
        assertTrue(error.getMessage().contains("must be public"));
    }

    @Test
    void traitsCannotAppearInRuntimeTypePositions() {
        IllegalArgumentException direct = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                          end

                          pub fnc use(Counter value) => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(direct.getMessage().contains("cannot be used as a runtime type"));

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define trait Counter as
                          end

                          pub fnc use(Option<Counter> value) => void {
                            return;
                          }
                        end
                        """)));

        assertTrue(nested.getMessage().contains("cannot be used as a runtime type"));
    }

    @Test
    void traitGenericSubstitutionRewritesMethodBodyTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  define trait Echo<T> as
                    pub echo(T value) => T {
                      val T copy = value;
                      return copy;
                    }
                  end

                  define class IntEcho with Echo<int> as
                  end

                  pub fnc run() => int {
                    val echo = new IntEcho();
                    return echo.echo(7);
                  }
                end
                """)));
    }

    @Test
    void traitNamesShareTheTypeNamespace() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module model as
                          define interface Named as
                            fnc name() => String;
                          end

                          define trait Named as
                            pub name() => String { return "trait"; }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("collides with an existing class/interface/type alias"));
    }

    @Test
    void traitsCannotMasqueradeAsRuntimeValueTypes() {
        IllegalArgumentException parameter = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait Stateful as
                            pub value() => int { return 1; }
                          end

                          fnc bad(Stateful value) => void {
                            return;
                          }
                        end
                        """)));
        assertTrue(parameter.getMessage().contains("no runtime/value type identity"));

        IllegalArgumentException allocation = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait Stateful as
                            pub value() => int { return 1; }
                          end

                          fnc bad() => void {
                            val value = new Stateful();
                            return;
                          }
                        end
                        """)));
        assertTrue(allocation.getMessage().contains("no runtime/value type identity"));
    }

    @Test
    void aggregateContractTraitAndAliasNamesShareOneTypeNamespace() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait Thing as
                            private val int state = 1;
                          end

                          struct Thing {
                            value: int;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("share one namespace"));
    }
    @Test
    void privateTraitMethodsRemainOwnedByTheirTraitAfterComposition() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  define trait SecretMath as
                    private helper() => int {
                      return 41;
                    }

                    pub answer() => int {
                      return self.helper() + 1;
                    }
                  end

                  define class Good with SecretMath as
                  end
                end
                """)));

        IllegalArgumentException leak = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          define trait SecretMath as
                            private helper() => int {
                              return 41;
                            }
                          end

                          define class Bad with SecretMath as
                            pub leak() => int {
                              return self.helper();
                            }
                          end
                        end
                        """)));

        assertTrue(leak.getMessage().contains("private to that trait"));
    }

}

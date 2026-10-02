package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LocalStructTypeTest {
    @Test
    void callableLocalStructCanDefineItsOwnEscapingReturnShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  pub fnc getat() => T {
                    struct T {
                      foo: string
                    }

                    return obj{
                      foo: 'hello'
                    };
                  }

                  pub fnc main() => void {
                    val result = getat();
                    stdio.println(result.foo);
                    return;
                  }
                end
                """)));
    }

    @Test
    void callableLocalStructNameDoesNotLeakIntoModuleTypeScope() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          pub fnc getat() => T {
                            struct T { foo: string }
                            return obj{foo: 'hello'};
                          }

                          fnc misuse() => void {
                            val T leaked = getat();
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("initializer for leaked"));
    }

    @Test
    void sameLocalStructNameCanBeReusedByDifferentCallablesWithoutCollision() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc left() => T {
                    struct T { left: string }
                    return obj{left: "L"};
                  }

                  fnc right() => T {
                    struct T { right: int }
                    return obj{right: 7};
                  }

                  pub fnc main() => void {
                    val a = left();
                    val b = right();
                    stdio.println(a.left);
                    stdio.println(b.right);
                    return;
                  }
                end
                """)));
    }

    @Test
    void localStructLiteralConversionRequiresExactFieldSetAndCompatibleTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc missing() => T {
                    struct T { foo: string; count: int }
                    return obj{foo: "hello"};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc extra() => T {
                    struct T { foo: string }
                    return obj{foo: "hello", extra: 1};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc wrong() => T {
                    struct T { foo: string }
                    return obj{foo: 42};
                  }
                end
                """)));
    }

    @Test
    void localStructLongFormAndShortFormAreEquivalentSyntaxSurfaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc short_form() => T {
                    struct T { foo: string }
                    return obj{foo: "short"};
                  }

                  fnc long_form() => T {
                    define struct T as
                      foo: string
                    end
                    return obj{foo: "long"};
                  }
                end
                """)));
    }

    @Test
    void localInterfacesRemainStorageFreeMethodContracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  fnc use_contract() => void {
                    interface LocalApi {
                      fnc ping() => int;
                    }
                    return;
                  }
                end
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app as
                          fnc bad_contract() => void {
                            interface LocalApi {
                              value: int;
                            }
                            return;
                          }
                        end
                        """));
        assertTrue(error.getMessage().contains("storage-free contracts"));
    }

    @Test
    void duplicateCallableLocalTypeNamesAreRejectedWithinOneLexicalBlock() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app as
                          fnc f() => void {
                            struct T { foo: string }
                            interface T { fnc ping() => int; }
                            return;
                          }
                        end
                        """)));
        assertTrue(error.getMessage().contains("duplicate callable-local type 'T'"));
    }

    @Test
    void structKeywordIsReservedAndModuleStructCanUseCompactSyntax() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app as
                  struct Point {
                    x: int
                    y: int
                  }

                  fnc make() => Point {
                    return obj{x: 1, y: 2};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app as
                  fnc bad() => void {
                    val int struct = 1;
                    return;
                  }
                end
                """));
    }
}

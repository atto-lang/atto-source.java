package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeOwnershipSyntaxTest {

    @Test
    void rtOwnershipOperationsAcceptCommandAndParenthesizedForms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc copy_command(int value) => int {
                  return rt copy value;
                }

                fnc copy_call(int value) => int {
                  return rt copy(value);
                }
                """)));
    }

    @Test
    void ordinaryClassArgumentPassesSameReferenceWithoutMovingCaller() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub val String name = "Alex";
                end

                fnc read_name(Person person) => String {
                  return person.name;
                }

                fnc ok() => void {
                  let Person p = new Person();
                  stdio.println(read_name(p));
                  stdio.println(read_name(p));
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void typeMutNameIsCanonicalMutableReferenceSyntax() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Person as
                  pub let String name = "Alex";
                end

                fnc rename(Person mut person) => void {
                  person.name = "Taylor";
                  return;
                }

                fnc ok() => void {
                  let Person p = new Person();
                  rename(p);
                  rename(p);
                  stdio.println(p.name);
                  return;
                }
                """)));
    }

    @Test
    void rtBorrowUsesSameReferenceAndBlocksOverlappingMutation() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub let String name = "Alex";
                        end

                        fnc rename(Person mut person) => void {
                          person.name = "Taylor";
                          return;
                        }

                        fnc bad() => void {
                          let Person p = new Person();
                          val view = rt borrow p;
                          rename(p);
                          stdio.println(view.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }

    @Test
    void rtTakeTransfersOwnershipAndRejectsLaterCallerUse() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Person as
                          pub val String name = "Alex";
                        end

                        fnc bad() => void {
                          let Person p = new Person();
                          let Person owned = rt take p;
                          stdio.println(owned.name);
                          stdio.println(p.name);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"), error.getMessage());
    }

    @Test
    void pointerStyleBorrowSyntaxRemainsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Person as
                        end

                        fnc bad(&Person person) => void {
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("pointer-style '&'"), error.getMessage());
    }

    @Test
    void rtParenthesesCanGroupWiderExpression() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc grouped(int left, int right) => int {
                  return rt copy(left + right);
                }

                fnc unary_precedence(int left, int right) => int {
                  return rt copy left + right;
                }
                """)));
    }
}

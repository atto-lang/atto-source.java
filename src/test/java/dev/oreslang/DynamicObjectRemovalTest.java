package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DynamicObjectRemovalTest {

    @Test
    void objIsNoLongerALanguageKeyword() {
        var tokens = new Lexer("obj").scan();
        assertEquals(Token.Type.IDENT, tokens.getFirst().type());
    }

    @Test
    void objLiteralSyntaxIsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app
                          fnc main(): void {
                            val value = obj{name: "ores"};
                            return;
                          }
                        end
                        """));

        assertTrue(error.getMessage().contains("statement") || error.getMessage().contains("expected"));
    }

    @Test
    void computedBacktickObjectKeySyntaxIsRejectedByLexer() {
        assertThrows(IllegalArgumentException.class, () -> new Lexer("\u0060key\u0060").scan());
    }

    @Test
    void dynamicStructTypeIsRejectedWithMigrationGuidance() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad(DynamicStruct<int> bag): void {
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("DynamicStruct<T> has been removed"));
    }

    @Test
    void dynamicStructConstructionIsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad(): void {
                            val bag = new DynamicStruct<int>();
                            return;
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("DynamicStruct<T> has been removed"));
    }

    @Test
    void structuralRecordsUseStaticMembersNotDynamicStringIndexing() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class StaticRecord as
                  pub val int foo;
                end

                fnc read({foo: int} value): int {
                  return value.foo;
                }

                fnc main(): int {
                  return read(new StaticRecord(7));
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc read({foo: int} value): int {
                  return value["foo"];
                }
                """)));
    }

    @Test
    void reservedCallableNamesRemainCallableOnly() {
        var tokens = new Lexer("stop do done").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  routine do(): int { return 2; }
                  fnc done(): int { return 3; }

                  pub fnc main(): int {
                    return stop() + do() + done();
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main(): void {
                    val stop = 1;
                    return;
                  }
                end
                """));
    }
}

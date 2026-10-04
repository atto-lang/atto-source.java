package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ReservedKeywordAndDynamicStructTest {
    @Test
    void stopDoDoneAreReservedLexically() {
        List<Token> tokens = new Lexer("stop do done").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());
    }

    @Test
    void reservedWordsRemainLegalCallableNamesAndInvocations() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc stop() => int { return 1; }
                fnc do() => int { return 2; }
                fnc done() => int { return 3; }

                pub fnc main() => int {
                  return stop() + do() + done();
                }
                """)));
    }

    @Test
    void reservedWordsCannotBeVariableOrParameterNames() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main() => void {
                  let int stop = 1;
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc nope(int do) => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main() => void {
                  const done = 3;
                  return;
                }
                """));
    }

    @Test
    void quotedAndReservedStaticObjectKeysAreAccepted() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  val x = obj{
                    'stop': 1,
                    "do": 2,
                    done: 3
                  };
                  stdio.println(x["stop"]);
                  stdio.println(x["do"]);
                  stdio.println(x["done"]);
                  return;
                }
                """));

        assertNotNull(program);
    }

    @Test
    void backtickObjectKeysAreDynamicStringsAndProduceDynamicStruct() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  val key = "runtime-key";
                  let DynamicStruct<int> values = obj{
                    'fixed': 1,
                    `key`: 2
                  };
                  values["another"] = 3;
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  val notAString = 42;
                  val values = obj{`notAString`: 1};
                  return;
                }
                """)));
    }

    @Test
    void dynamicStructRequiresStringKeysAndEnforcesItsValueType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  let DynamicStruct<String> values = new DynamicStruct<String>();
                  values["alpha"] = "one";
                  values["beta"] = "two";
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  let DynamicStruct<String> values = new DynamicStruct<String>();
                  values[1] = "bad-key";
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  let DynamicStruct<String> values = new DynamicStruct<String>();
                  values["bad-value"] = 42;
                  return;
                }
                """)));
    }
}

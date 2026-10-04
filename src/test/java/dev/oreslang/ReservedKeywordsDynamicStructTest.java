package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ReservedKeywordsDynamicStructTest {
    @Test
    void objLiteralSyntaxIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): int {
                  val value = obj{foo: 1};
                  return 0;
                }
                """));
    }

    @Test
    void objIsAvailableAsAnOrdinaryIdentifier() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): int {
                  val obj = 4;
                  return obj;
                }
                """)));
    }

    @Test
    void legacyDynamicStructTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc make(): DynamicStruct<int> {
                  return new DynamicStruct<int>();
                }

                pub fnc main(): void { return; }
                """)));
    }

    @Test
    void structuralRecordContractsRequireAStaticallyDeclaredConcreteShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Result as
                  pub val int foo;
                  pub val string bar;
                end

                fnc result(): {foo: int, bar: string} {
                  return new Result(5, "x");
                }

                pub fnc main(): void {
                  const {foo, bar} = result();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """)));
    }
}

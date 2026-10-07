package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class InferredStructReadonlyBoundaryTest {
    @Test void mutableDestructureAliasIsRejectedAtBinding() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val [item] = [struct infer{foo: "a"}];
                  return;
                }
                """)));
    }

    @Test void mutableForOfAliasIsRejectedAtBinding() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  for val item of arr[struct infer{foo: "a"}] do
                    stdio.println(item.foo);
                  done
                  return;
                }
                """)));
    }

    @Test void mutableGenericParameterCannotReceiveReadonlyInferredRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc touch<T>(T mut value): void {
                  return;
                }
                fnc bad(): void {
                  touch(struct infer{foo: "a"});
                  return;
                }
                """)));
    }
    @Test void structuralCastCannotUpgradeReadonlyRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = struct infer{foo: "a"};
                  let mut alias = source as {foo: string};
                  alias.foo = "b";
                  return;
                }
                """)));
    }

}

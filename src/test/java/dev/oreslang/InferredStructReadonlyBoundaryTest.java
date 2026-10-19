package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InferredStructReadonlyBoundaryTest {
    @Test void mutableDestructureCanOwnConcreteStructElement() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): string {
                  val [item] = [infer struct{foo: "a"}];
                  item.foo = "b";
                  return item.foo;
                }
                """)));
    }

    @Test void mutableForOfAliasCanUseConcreteStructElement() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): void {
                  for val item of arr[infer struct{foo: "a"}] do
                    item.foo = "b";
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
                  touch(record{foo: "a"});
                  return;
                }
                """)));
    }
    @Test void structuralCastCannotUpgradeReadonlyRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = record{foo: "a"};
                  let mut alias = source as {foo: string};
                  alias.foo = "b";
                  return;
                }
                """)));
    }

    @Test void recordReturnPreservesReadonlyCapability() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc keep(): {foo: string} {
                  return record{foo: "a"};
                }
                """)));
    }

    @Test void recordRoundTripCannotGainMutableBinding() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc identity({foo: string} value): {foo: string} {
                  return value;
                }
                fnc bad(): void {
                  val result = identity(record{foo: "a"});
                  result.foo = "b";
                  return;
                }
                """)));
    }

    @Test void collectionInsertionAcceptsOwnedConcreteStructOfSameShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): void {
                  val items = arr[struct{foo: string}{foo: "mutable"}];
                  items.add(infer struct{foo: "owned"});
                  return;
                }
                """)));
    }

    @Test void genericIdentityPreservesReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc identity<T>(T value): T {
                  return value;
                }
                fnc bad(): void {
                  let mut upgraded = identity(record{foo: "a"});
                  upgraded.foo = "b";
                  return;
                }
                """)));
    }

    @Test void structuralBorrowViewMayReadConcreteStruct() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc read(structural {foo: string} value): string {
                  return value.foo;
                }
                fnc ok(): string {
                  return read(infer struct{foo: "a"});
                }
                """)));
    }

    @Test void typeTestBindingPreservesReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = record{foo: "a"};
                  if source is type {foo: string} narrowed then
                    let mut alias = narrowed;
                    alias.foo = "b";
                  fi
                  return;
                }
                """)));
    }

    @Test void typedMatchBindingPreservesReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = record{foo: "a"};
                  match source
                    is {foo: string} narrowed -> {
                      let mut alias = narrowed;
                      alias.foo = "b";
                      return;
                    }
                    else -> { return; }
                  end
                }
                """)));
    }

    @Test void ownershipTypeTestBindingPreservesReadonlyCapability() {
        var program = Parser.parse("""
                fnc bad(): void {
                  const source = record{foo: "a"};
                  if source is type {foo: string} narrowed then
                    let mut alias = narrowed;
                    return;
                  fi
                  return;
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
    }

    @Test void ownershipTypedMatchBindingPreservesReadonlyCapability() {
        var program = Parser.parse("""
                fnc bad(): void {
                  const source = record{foo: "a"};
                  match source
                    is {foo: string} narrowed -> {
                      let mut alias = narrowed;
                      return;
                    }
                    else -> { return; }
                  end
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
    }

}

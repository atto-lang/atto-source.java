package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Both accepted parameter orders must preserve the same typing and ownership rules. */
final class AnonymousStructParameterOrderTest {
    @Test
    void parsesBothOrdersForEmptyNestedGenericAndMixedParameterLists() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc name_first(item: struct{name: string, age: int}): void {}
                fnc type_first(struct{name: string, age: int} item): void {}
                fnc empty_name_first(item: struct{}): void {}
                fnc empty_type_first(struct{} item): void {}
                fnc nested_name_first(item: struct{user: struct{name: string, age: int}}): void {}
                fnc nested_type_first(struct{user: struct{name: string, age: int}} item): void {}
                fnc generic_name_first(item: Option<struct{id: int}>): void {}
                fnc generic_type_first(Option<struct{id: int}> item): void {}
                fnc mixed(int count, item: struct{id: int}, bool enabled,
                          struct{ready: bool} flag): void {}
                """)));
    }

    @Test
    void bothOrdersTypecheckAndPreserveFieldAccess() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc name_first(item: struct{id: int}): int { return item.id; }
                fnc type_first(struct{id: int} item): int { return item.id; }
                fnc exercise(): int {
                    return name_first(infer struct{id: 17})
                         + type_first(infer struct{id: 25});
                }
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void explicitStructuralWidthStillRequiresReadonlyContract() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc read(structural struct{id: int} item): int { return item.id; }
                fnc exercise(): int {
                    return read(record{id: 17, label: "extra"});
                }
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));

        assertThrows(RuntimeException.class, () -> Parser.parse("""
                fnc forbidden(structural struct{id: int} mut item): void {}
                """));
    }

    @Test
    void rejectsMalformedAndAmbiguousParameterDeclarations() {
        String[] invalid = {
                "fnc bad(item: struct{id: int, id: string}): void {}",
                "fnc bad(item: struct{id:}): void {}",
                "fnc bad(item: struct{id int}): void {}",
                "fnc bad(item: ): void {}",
                "fnc bad(struct{id: int}): void {}",
                "fnc bad(item: struct{id: int} other): void {}",
                "fnc bad(struct{id: int}: item): void {}"
        };
        for (String source : invalid) {
            assertThrows(RuntimeException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void rejectsMissingFieldsAndWrongFieldTypesInEitherOrder() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc read(item: struct{id: int}): int { return item.id; }
                fnc bad(): int { return read(infer struct{name: "missing"}); }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc read(struct{id: int} item): int { return item.id; }
                fnc bad(): int { return read(infer struct{id: "wrong type"}); }
                """)));
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AggregateComputedAccessTest {
    private static Ast.Program typed(String source) {
        return TypeChecker.check(Parser.parse(source));
    }

    @Test
    void dynamicReadDomainsStayDistinctAcrossTupleRecordStructAndEntry() {
        assertDoesNotThrow(() -> typed("""
                fnc main(): int {
                  const pair = tuple {20, 22};
                  const int tupleIndex = 1;
                  const recordValue = record{x: 20, y: 22};
                  const string recordKey = "x";
                  const Point point = infer<Point> struct{x: 20, y: 22};
                  const string structKey = "y";
                  const entry = Entry.of(20, 22);
                  const string entryKey = "key";
                  return pair[tupleIndex]
                       + recordValue[recordKey]
                       + point[structKey]
                       + entry[entryKey];
                }
                """));
    }

    @Test
    void tupleRejectsStringKeysWhileNamedProductsRejectIntegerKeys() {
        IllegalArgumentException tuple = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const pair = tuple {1, 2};
                          return pair["0"];
                        }
                        """));
        assertTrue(tuple.getMessage().contains("index"), tuple.getMessage());

        IllegalArgumentException record = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const row = record{x: 1};
                          return row[0];
                        }
                        """));
        assertTrue(record.getMessage().contains("field key"), record.getMessage());

        IllegalArgumentException struct = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const Point point = infer<Point> struct{x: 1};
                          return point[0];
                        }
                        """));
        assertTrue(struct.getMessage().contains("field key"), struct.getMessage());

        IllegalArgumentException entry = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const item = Entry.of(1, 2);
                          return item[0];
                        }
                        """));
        assertTrue(entry.getMessage().contains("field key"), entry.getMessage());
    }

    @Test
    void staticallyKnownUnknownNamedKeysFailClosed() {
        IllegalArgumentException record = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const row = record{x: 1};
                          return row["missing"];
                        }
                        """));
        assertTrue(record.getMessage().contains("unknown record/struct field key"), record.getMessage());

        IllegalArgumentException struct = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const Point point = infer<Point> struct{x: 1};
                          return point["missing"];
                        }
                        """));
        assertTrue(struct.getMessage().contains("unknown record/struct field key"), struct.getMessage());

        IllegalArgumentException entry = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const item = Entry.of(1, 2);
                          return item["missing"];
                        }
                        """));
        assertTrue(entry.getMessage().contains("unknown Entry field key"), entry.getMessage());
    }

    @Test
    void indexedMutationPreservesAggregateAuthorityBoundaries() {
        IllegalArgumentException record = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): void {
                          val row = record{x: 1};
                          const string key = "x";
                          row[key] = 2;
                          return;
                        }
                        """));
        assertTrue(record.getMessage().contains("readonly"), record.getMessage());

        IllegalArgumentException entry = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): void {
                          val item = Entry.of(1, 2);
                          const string key = "value";
                          item[key] = 3;
                          return;
                        }
                        """));
        assertTrue(entry.getMessage().contains("immutable"), entry.getMessage());

        Ast.Program mutableStruct = typed("""
                fnc ok(): int {
                  val Point point = infer<Point> struct{x: 1, y: 2};
                  const string key = "x";
                  point[key] = 7;
                  return point.x;
                }
                """);
        assertDoesNotThrow(() -> OwnershipChecker.check(mutableStruct));

        Ast.Program constStruct = typed("""
                fnc bad(): int {
                  const Point point = infer<Point> struct{x: 1, y: 2};
                  const string key = "x";
                  point[key] = 7;
                  return point.x;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(constStruct));
    }
    @Test
    void heterogeneousTupleDynamicIndexIsAUnionAndCannotNarrowImplicitly() {
        Ast.Program accepted = typed("""
                fnc good(int index): int | string {
                  const pair = tuple {7, "seven"};
                  const int | string value = pair[index];
                  return value;
                }
                """);
        assertDoesNotThrow(() -> OwnershipChecker.check(accepted));

        IllegalArgumentException narrowed = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(int index): int {
                          const pair = tuple {7, "seven"};
                          const int value = pair[index];
                          return value;
                        }
                        """));
        assertTrue(
                narrowed.getMessage().contains("expected int")
                        || narrowed.getMessage().contains("type"),
                narrowed.getMessage());
    }

    @Test
    void constantTupleIndexOutsideArityIsRejectedBeforeRuntime() {
        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> typed("""
                        fnc bad(): int {
                          const pair = tuple {1, 2};
                          return pair[2];
                        }
                        """));
        assertTrue(invalid.getMessage().contains("outside arity"),
                invalid.getMessage());
    }

}
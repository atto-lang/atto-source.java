package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Record;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safety regression for issue #853: writable aliases cannot widen slot types,
 * while readonly structural views retain safe covariance and width subtyping.
 */
final class RecordWriteVarianceTest {
    @Test
    void writableFieldIsInvariantEvenWithNumericWidening() {
        Record source = new Record(Map.of("value", Types.Primitive.INT));
        Record widened = new Record(Map.of("value", Types.Primitive.FLOAT));
        assertFalse(Types.isAssignable(source, widened));
        assertFalse(Types.isAssignable(widened, source));
        assertTrue(Types.isAssignable(source, new Record(Map.of("value", Types.Primitive.INT))));
    }

    @Test
    void writableFieldCannotLaunderAnUnknownType() {
        Record source = new Record(Map.of("value", Types.Primitive.INT));
        Record unknown = new Record(Map.of("value", Types.Unknown.INSTANCE));
        assertFalse(Types.isAssignable(source, unknown));
        assertFalse(Types.isAssignable(unknown, source));
    }

    @Test
    void writableTypeMatrixRejectsEveryNonidenticalFieldType() {
        Types.Type[] types = {
                Types.Primitive.INT, Types.Primitive.FLOAT, Types.Primitive.DECIMAL,
                Types.Primitive.COMPLEX, Types.Primitive.BOOL, Types.Primitive.STRING,
                Types.Unknown.INSTANCE,
                new Types.Named("Box", java.util.List.of(Types.Primitive.INT))
        };
        for (Types.Type from : types) {
            for (Types.Type to : types) {
                Record source = new Record(Map.of("payload", from), false);
                Record writableTarget = new Record(Map.of("payload", to), false);
                assertEquals(from.equals(to), Types.isAssignable(source, writableTarget),
                        () -> "unsafe writable field conversion " + from + " -> " + to);
            }
        }
    }

    @Test
    void readonlySourceCannotRegainWriteAuthority() {
        Record readonly = new Record(Map.of("value", Types.Primitive.INT), true);
        Record writable = new Record(Map.of("value", Types.Primitive.INT), false);
        assertFalse(Types.isAssignable(readonly, writable));
        assertTrue(Types.isAssignable(writable, readonly));
        assertTrue(Types.isAssignable(readonly, readonly));
    }

    @Test
    void readonlyWidthAndNumericCovarianceRemainValid() {
        Record source = new Record(Map.of(
                "value", Types.Primitive.INT,
                "extra", Types.Primitive.STRING));
        Record readonlyView = new Record(Map.of("value", Types.Primitive.FLOAT), true);
        assertTrue(Types.isAssignable(source, readonlyView));
        assertFalse(Types.isAssignable(source,
                new Record(Map.of("value", Types.Primitive.FLOAT), false)));
    }

    @Test
    void nestedMutableSlotsCannotBeWidened() {
        Record nested = new Record(Map.of("inner",
                new Record(Map.of("value", Types.Primitive.INT))));
        Record widened = new Record(Map.of("inner",
                new Record(Map.of("value", Types.Primitive.FLOAT))));
        assertFalse(Types.isAssignable(nested, widened));
    }

    @Test
    void structuralParameterCannotMutateBorrowedFields() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Position
                          value: int;
                        end
                        fnc bad(structural Position position): void {
                          position.value = 3;
                          return;
                        }
                        """)));
    }

    @Test
    void structuralParameterStillPermitsReadOnlyWidthSubtyping() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface Position
                  value: int;
                end
                define class Point as
                  pub val int value = 3;
                  pub val int other = 5;
                end
                fnc inspect(structural Position position): int {
                  return position.value;
                }
                fnc ok(): int {
                  return inspect(new Point());
                }
                """)));
    }
}

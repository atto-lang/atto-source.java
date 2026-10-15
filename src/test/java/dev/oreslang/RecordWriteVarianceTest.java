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
    void unknownSourceFieldCannotPretendToBeConcreteReadonlyField() {
        Record unknown = new Record(Map.of("value", Types.Unknown.INSTANCE));
        Record concrete = new Record(Map.of("value", Types.Primitive.INT), true);
        assertFalse(Types.isAssignable(unknown, concrete));
        assertTrue(Types.isAssignable(unknown,
                new Record(Map.of("value", Types.Unknown.INSTANCE), true)));
    }

    @Test
    void structuralPatternDoesNotGrantWriteAccessOnMutableRecords() {
        for (String shape : new String[]{"{ bar: string }", "Readable"}) {
            String source = """
                    define trait Readable as
                      bar: string;
                    end
                    fnc bad({ bar: string } value): void {
                      match value over
                        on structural %s narrowed -> {
                          narrowed.bar = "after";
                        }
                        on _ -> { return; }
                      end
                      return;
                    }
                    """.formatted(shape);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(failure.getMessage().contains("readonly"), failure.getMessage());
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
    void structuralPatternStillReadsInferredReadonlyRecord() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc read(): string {
                  const value = infer struct{bar: "ok"};
                  match value over
                    on structural { bar: string } narrowed -> {
                      return narrowed.bar;
                    }
                    on _ -> { return "missing"; }
                  end
                }
                """)));
    }

    @Test
    void readonlyNamedStructuralPatternRetainsMethodDispatch() {
        // The match binding is now a readonly Record rather than a nominal
        // alias. Ensure its shape still carries callable interface slots.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define trait Readable as
                    bar: string;
                    fnc show(string value) => void;
                  end
                  define class Base as end
                  define class Candidate extends Base as
                    pub val string bar = "ok";
                    pub show(string value): void { return; }
                  end
                  fnc inspect(Base value): int {
                    match value over
                      on structural Readable view -> {
                        view.show(view.bar);
                        return 1;
                      }
                      on _ -> { return 0; }
                    end
                  }
                end
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

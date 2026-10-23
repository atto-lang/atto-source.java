package dev.oreslang;

import dev.oreslang.types.Types;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types.Record;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Rebased-to-main writable structural variance regression for #853. */
final class RecordWriteVarianceCurrentMainTest {
    @Test void writableFieldTypeMustMatchExactly() {
        Types.Type[] candidates = {
                Types.Primitive.INT, Types.Primitive.FLOAT,
                Types.Primitive.DECIMAL, Types.Primitive.BOOL,
                Types.Unknown.INSTANCE,
                new Types.Generic("T"),
                new Types.Named("Box", List.of(Types.Primitive.INT))
        };
        for (Types.Type from : candidates) {
            for (Types.Type to : candidates) {
                var source = new Record(Map.of("field",from));
                var target = new Record(Map.of("field",to));
                assertEquals(from.equals(to), Types.isAssignable(source,target),
                        () -> "writable covariance attempted: "+from+" -> "+to);
            }
        }
    }

    @Test void readonlyWidthAndSafeNumericCovarianceStillWork() {
        var source = new Record(Map.of(
                "field",Types.Primitive.INT,"extra",Types.Primitive.STRING));
        var narrow = new Record(Map.of("field",Types.Primitive.FLOAT),true);
        assertTrue(Types.isAssignable(source,narrow));
        assertFalse(Types.isAssignable(source,
                new Record(Map.of("field",Types.Primitive.FLOAT))));
    }

    @Test void readonlySourceCannotRecoverWriterThroughRecord() {
        var frozen = new Record(Map.of("field",Types.Primitive.INT),true);
        var writable = new Record(Map.of("field",Types.Primitive.INT));
        assertFalse(Types.isAssignable(frozen,writable));
        assertTrue(Types.isAssignable(writable,frozen));
    }

    @Test void readonlyTargetMustRejectUnknownGenericAndNestedUncertainty() {
        var readonly = new Record(Map.of("field",Types.Primitive.INT),true);
        assertFalse(Types.isAssignable(new Record(Map.of(
                "field",Types.Unknown.INSTANCE)),readonly));
        assertFalse(Types.isAssignable(new Record(Map.of(
                "field",new Types.Generic("T"))),readonly));
        assertFalse(Types.isAssignable(new Record(Map.of(
                "field",new Types.Named("Box",List.of(Types.Unknown.INSTANCE)))),
                new Record(Map.of("field",new Types.Named("Box",List.of(
                        Types.Primitive.INT))),true)));
    }

    @Test void nestedWritableAliasesDoNotWidenNestedFields() {
        var original = new Record(Map.of("child",
                new Record(Map.of("value",Types.Primitive.INT))));
        var widened = new Record(Map.of("child",
                new Record(Map.of("value",Types.Primitive.FLOAT))));
        assertFalse(Types.isAssignable(original,widened));
    }

    @Test void structShapeRemainsClosed() {
        var struct = Record.struct(Map.of(
                "field",Types.Primitive.INT,"extra",Types.Primitive.BOOL));
        var fewer = Record.struct(Map.of("field",Types.Primitive.INT));
        assertFalse(Types.isAssignable(struct,fewer));
        assertTrue(Types.isAssignable(struct,struct));
    }
    @Test void structuralParameterIsNotAWriteAuthorityInGuestSource() {
        var parsed = assertDoesNotThrow(() -> Parser.parse("""
                define interface Position
                  value: int;
                end
                fnc bad(structural Position p): void {
                  p.value = 3;
                  return;
                }
                """), "the guest-source negative must parse successfully");
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(rejected.getMessage() != null
                        && rejected.getMessage().toLowerCase(java.util.Locale.ROOT)
                                .contains("readonly"),
                "must reject due to readonly authority, not an unrelated error: " + rejected);
    }

    @Test void sourceStructuralParameterRetainsReadOnlyWidthSubtyping() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface Position
                  value: int;
                end
                define class Point as
                  pub val int value = 3;
                  pub val int other = 5;
                end
                fnc inspect(structural Position p): int {
                  return p.value;
                }
                fnc ok(): int {
                  return inspect(new Point());
                }
                """)));
    }

    @Test void structuralPatternProvesReadShapeNotWriteAuthority() {
        var parsed = assertDoesNotThrow(() -> Parser.parse("""
                fnc bad({value: int} input): void {
                  match input over
                    on structural { value: int } narrowed -> {
                      narrowed.value = 9;
                    }
                    on _ -> { return; }
                  end
                  return;
                }
                """), "the structural pattern guest source must parse");
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(rejected.getMessage() != null
                        && rejected.getMessage().toLowerCase(java.util.Locale.ROOT)
                                .contains("readonly"),
                "pattern must reject mutation of the readonly view: " + rejected);
    }

    @Test void structuralPatternRetainsReadonlyFieldProjection() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): string {
                  const value = infer struct{bar: "ok"};
                  match value over
                    on structural { bar: string } narrowed -> {
                      return narrowed.bar;
                    }
                  end
                }
                """)));
    }

    @Test void namedStructuralPatternRejectsMutationAfterSuccessfulParse() {
        var parsed = assertDoesNotThrow(() -> Parser.parse("""
                define trait Readable as
                  bar: string;
                end
                fnc bad({ bar: string } input): void {
                  match input over
                    on structural Readable narrowed -> {
                      narrowed.bar = "changed";
                    }
                    on _ -> { return; }
                  end
                  return;
                }
                """), "nominal structural contract fixture must parse");
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(rejected.getMessage() != null
                        && rejected.getMessage().toLowerCase(java.util.Locale.ROOT)
                                .contains("readonly"),
                "the checker must reject mutation, not unrelated syntax: " + rejected);
    }

    @Test void namedReadonlyStructuralPatternRetainsCallableContractMethods() {
        var parsed = assertDoesNotThrow(() -> Parser.parse("""
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
                """), "structural trait method-dispatch fixture must parse");
        assertDoesNotThrow(() -> TypeChecker.check(parsed),
                "readonly structural contract must not erase callable method slots");
    }

}

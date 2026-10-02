package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StructCategoryBarrierTest {

    @Test
    void nominalClassParameterRejectsSameShapeStruct() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class ClassPoint as
                          pub val int x = 0;
                          pub val int y = 0;
                        end

                        struct StructPoint {
                          pub x: int;
                          pub y: int;
                        }

                        fnc takes_class(ClassPoint value) => void {
                          return;
                        }

                        fnc bad() => void {
                          val StructPoint point = StructPoint { x = 1, y = 2 };
                          takes_class(point);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("expected"));
    }

    @Test
    void nominalStructParameterRejectsSameShapeClass() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class ClassPoint as
                          pub val int x = 0;
                          pub val int y = 0;
                        end

                        struct StructPoint {
                          pub x: int;
                          pub y: int;
                        }

                        fnc takes_struct(StructPoint value) => void {
                          return;
                        }

                        fnc bad() => void {
                          val ClassPoint point = new ClassPoint();
                          takes_struct(point);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("expected"));
    }

    @Test
    void explicitStructuralBoundaryCanCrossClassStructCategory() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class ClassPoint as
                  pub val int x = 0;
                  pub val int y = 0;
                end

                struct StructPoint {
                  pub x: int;
                  pub y: int;
                }

                fnc read_x(@Structural ClassPoint value) => int {
                  return value.x;
                }

                fnc ok() => int {
                  val StructPoint point = StructPoint { x = 7, y = 9 };
                  return read_x(point);
                }
                """)));
    }
}

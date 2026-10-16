package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-callable regression slice of contracts/structural/v1/README.md. */
final class StructuralCallableParityTest {
    @Test
    void allSynchronousCallableSpellingsHonorExplicitStructuralParameters() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Position
                    x: int;
                  end
                  define class Coordinate as
                    pub val int x = 7;
                    pub val int y = 11;
                  end
                  fnc byFunction(structural Position value): int {
                    return value.x;
                  }
                  routine byRoutine(structural Position value): int {
                    return value.x;
                  }
                  define class Consumer as
                    pub method byMethod(structural Position value): int {
                      return value.x;
                    }
                    pub byBareMethod(structural Position value): int {
                      return value.x;
                    }
                    pub static fnc byStatic(structural Position value): int {
                      return value.x;
                    }
                  end
                  fnc callAll(): int {
                    val consumer = new Consumer();
                    return byFunction(new Coordinate())
                      + byRoutine(new Coordinate())
                      + consumer.byMethod(new Coordinate())
                      + consumer.byBareMethod(new Coordinate())
                      + Consumer.byStatic(new Coordinate());
                  }
                end
                """)));
    }

    @Test
    void structuralDeclarationModifierNeverSilentlyWidensNominalParameter() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Position
                    x: int;
                  end
                  define class Coordinate as
                    pub val int x = 7;
                  end
                  @structural
                  routine nominalOnly(Position value): int { return value.x; }
                  fnc invalid(): int { return nominalOnly(new Coordinate()); }
                end
                """)));
    }

    @Test
    void wrongShapeIsRejectedAtEachCallableBoundary() {
        for (String call : new String[] {
                "byFunction(new Wrong())",
                "byRoutine(new Wrong())",
                "consumer.byMethod(new Wrong())",
                "consumer.byBareMethod(new Wrong())",
                "Consumer.byStatic(new Wrong())"
        }) {
            String source = """
                    define module app
                      define interface Position
                        x: int;
                      end
                      define class Wrong as
                        pub val string x = "not-an-int";
                      end
                      fnc byFunction(structural Position value): int { return value.x; }
                      routine byRoutine(structural Position value): int { return value.x; }
                      define class Consumer as
                        pub method byMethod(structural Position value): int { return value.x; }
                        pub byBareMethod(structural Position value): int { return value.x; }
                        pub static fnc byStatic(structural Position value): int { return value.x; }
                      end
                      fnc invalid(): int { return %s; }
                    end
                    """.formatted(call);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), call);
        }
    }

    @Test
    void structuralMatchWorksInsideRoutineAndInstanceMethod() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Base as end
                  define class Candidate extends Base as
                    pub val int x = 7;
                  end
                  routine classify(Base value): int {
                    match value over
                      on structural { x: int } found -> { return found.x; }
                      on _ -> { return 0; }
                    end
                  }
                  define class Consumer as
                    pub method classify(Base value): int {
                      match value over
                        on structural { x: int } found -> { return found.x; }
                        on _ -> { return 0; }
                      end
                    }
                  end
                  fnc callBoth(): int {
                    val consumer = new Consumer();
                    return classify(new Candidate()) + consumer.classify(new Candidate());
                  }
                end
                """)));
    }
}

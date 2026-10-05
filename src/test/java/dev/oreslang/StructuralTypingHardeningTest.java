package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralTypingHardeningTest {
    @Test
    void canonicalStructuralKeywordAcceptsWidthSubtypingWithoutNominalImplements() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface PointLike
                    x: int;
                    y: int;
                  end

                  define class Point3d as
                    pub val int x = 1;
                    pub val int y = 2;
                    pub val int z = 3;
                  end

                  fnc sum(structural PointLike point): int {
                    return point.x + point.y;
                  }

                  fnc ok(): int {
                    return sum(new Point3d());
                  }
                end
                """)));
    }

    @Test
    void nominalParameterStillRequiresNominalRelationship() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface PointLike
                    x: int;
                  end

                  define class Point as
                    pub val int x = 1;
                  end

                  fnc take(PointLike point): int { return point.x; }
                  fnc bad(): int { return take(new Point()); }
                end
                """)));
    }

    @Test
    void classContractsCanBeUsedStructurallyAndOnlyPublicShapeCounts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Contract as
                    pub val int id = 1;
                    pub render(int value): String { return "ok"; }
                    private val int secret = 7;
                  end

                  define class Candidate as
                    pub val int id = 2;
                    pub val int extra = 3;
                    pub render(int value): String { return "candidate"; }
                  end

                  fnc use(structural Contract value): String {
                    return value.render(value.id);
                  }

                  fnc ok(): String { return use(new Candidate()); }
                end
                """)));
    }

    @Test
    void structuralMethodsCheckFullSignatureWithNominalVariance() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Base as end
                  define class Derived extends Base as end

                  define interface Handler
                    fnc handle(Derived value) => Base;
                  end

                  define class Wider as
                    pub handle(Base value): Derived { return new Derived(); }
                  end

                  fnc invoke(structural Handler handler, Derived value): Base {
                    return handler.handle(value);
                  }

                  fnc ok(Derived value): Base {
                    return invoke(new Wider(), value);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Handler
                    fnc handle(int value) => String;
                  end

                  define class Wrong as
                    pub handle(String value): int { return 1; }
                  end

                  fnc invoke(structural Handler handler): String {
                    return handler.handle(1);
                  }

                  fnc bad(): String { return invoke(new Wrong()); }
                end
                """)));
    }

    @Test
    void nominalInterfaceValuesCanFlowIntoStructuralSupercontracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Small
                    fnc run(int value) => int;
                  end

                  define interface Large
                    fnc run(int value) => int;
                    extra: int;
                  end

                  fnc use(structural Small value): int {
                    return value.run(1);
                  }

                  fnc ok(Large value): int {
                    return use(value);
                  }
                end
                """)));
    }

    @Test
    void structuralViewsCannotBecomeMutableAsyncOrActorMessages() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Shape
                    value: int;
                  end
                  fnc bad(structural Shape mut value): void { return; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Shape
                    value: int;
                  end
                  async fnc bad(structural Shape value): int { return value.value; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Shape
                    value: int;
                  end
                  actor fnc bad(structural Shape value): void { return; }
                end
                """)));
    }

    @Test
    void actorIdentityCannotBeErasedByStructuralTyping() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define actor Worker {
                    pub run(): int { return 1; }
                  }

                  fnc bad(structural Worker worker): int {
                    return worker.run();
                  }
                end
                """)));
    }
}

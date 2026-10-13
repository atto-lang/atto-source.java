package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ColonClassFieldInitializerTest {
    @Test
    void acceptsCanonicalAndCompatibleInitializersWithoutSemicolons() {
        for (String separator : new String[]{":", "="}) {
            Ast.Program program = Parser.parse("""
                    define class Foo as
                      final int foo%s 5
                      string bar%s "100"
                    end
                    """.formatted(separator, separator));
            assertDoesNotThrow(() -> TypeChecker.check(program));
            Ast.ClassDecl foo = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
            assertEquals(Ast.BindingKind.VAL, foo.fields().get(0).bindingKind());
            assertEquals(Ast.BindingKind.LET, foo.fields().get(1).bindingKind());
        }
    }

    @Test
    void finalConstLiteralHasTheSameFixedSlotBindingAsFinal() {
        Ast.Program program = Parser.parse("""
                define class Foo as
                  final int baseline: 5
                  final const int number: 5
                  final const string label: "ready"
                  final const bool enabled: true
                end
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        Ast.ClassDecl foo = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(4, foo.fields().size());
        foo.fields().forEach(field -> assertEquals(Ast.BindingKind.VAL, field.bindingKind()));
    }

    @Test
    void explicitFinalCannotBeShadowedInDirectOrTransitiveSubclass() {
        for (String source : new String[]{
                """
                define class Base as
                  pub final int token: 5
                end
                define class Child extends Base as
                  int token: 6
                end
                """,
                """
                define class Base as
                  final const int token: 5
                end
                define class Middle extends Base as
                  int unrelated: 1
                end
                define class Child extends Middle as
                  final int token: 6
                end
                """
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(error.getMessage().contains("cannot shadow final field"), error.getMessage());
        }
    }

    @Test
    void finalConstIsNotWritableFromMutableMethod() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  final const int token: 5
                  change(mut self)(): void {
                    self.token = 6;
                  }
                end
                """)));
    }

    @Test
    void finalConstRejectsRuntimeExpressionsAndMutableReferentWithoutDroppingConst() {
        IllegalArgumentException computed = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Foo as
                          final const int value: calculate()
                        end
                        """)));
        assertTrue(computed.getMessage().contains("compile-time constant"), computed.getMessage());
        IllegalArgumentException mutable = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Foo as
                          final const mut Array<int> values: [1, 2]
                        end
                        """));
        assertTrue(mutable.getMessage().contains("mutable referent"), mutable.getMessage());
    }

    @Test
    void preservesFieldTypeCheckingAndFinalSlotSemantics() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  int count: "not an integer"
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  final int count: 5
                  change(mut self)(): void {
                    self.count = 9;
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  int count: 5
                  change(mut self)(): void {
                    self.count = 9;
                  }
                end
                """)));
    }

    @Test
    void leavesNameFirstTypeAnnotationsAndMethodsUnambiguous() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Foo as
                  bar: string = "100"
                  final(): int {
                    return 5;
                  }
                end
                """));
    }

    @Test
    void finalizedInitializerCannotBeOverwrittenByConstructorOrMethod() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  final int count: 5
                  constructor() {
                    self.count = 9;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  val int count = 5;
                  constructor() {
                    self.count = 9;
                  }
                end
                """)));
    }

    @Test
    void preservesConstructorInitializationOfLegacyUninitializedFixedField() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  val int count;
                  constructor() {
                    self.count = 5;
                  }
                end
                """)));
    }

    @Test
    void rejectsSameLineAdjacentColonFieldsAndDuplicateNames() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Foo as
                  int one: 1 int two: 2
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  int one: 1
                  final int one: 2
                end
                """)));
    }

    @Test
    void rejectsInheritedFinalWritesAndDeferredConstructorClosures() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Parent as
                  pub final int token: 42
                end
                define class Child extends Parent as
                  update(mut self)(): void {
                    self.token = 7;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  val int late;
                  constructor() {
                    val callback = || -> {
                      self.late = 99;
                    };
                  }
                end
                """)));
    }

    @Test
    void acceptsNestedInitializerColonWithoutReinterpretingItAsFieldDelimiter() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  int count: true ? 5 : 9
                  string text: "a:b=c"
                end
                """)));
    }

    @Test
    void rejectsDestructiveTakeFromFinalClassSlot() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  pub final int token: 42
                end
                fnc steal(): int {
                  val Foo value = new Foo();
                  return rt take(value.token);
                }
                """)));
    }

    @Test
    void finalDoesNotConferMutableReferentCapability() {
        Ast.Program program = Parser.parse("""
                define class Foo as
                  final Array<int> readOnly: [1, 2]
                  final mut Array<int> writable: [3, 4]
                  mut Array<int> mutableAndReassignable: [5]
                end
                """);
        Ast.ClassDecl foo = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.BindingKind.VAL, foo.fields().get(0).bindingKind());
        assertFalse(foo.fields().get(0).mutableReferent());
        assertEquals(Ast.BindingKind.VAL, foo.fields().get(1).bindingKind());
        assertTrue(foo.fields().get(1).mutableReferent());
        assertEquals(Ast.BindingKind.LET, foo.fields().get(2).bindingKind());
        assertTrue(foo.fields().get(2).mutableReferent());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void semicolonFreeFieldBoundaryAdmitsFinalMutAndMutOnFollowingLines() {
        Ast.ClassDecl foo = (Ast.ClassDecl) Parser.parse("""
                define class Foo as
                  string label = "100"
                  final mut Array<int> items: [1, 2]
                  mut int count: 3
                end
                """).modules().getFirst().declarations().getFirst();
        assertEquals(3, foo.fields().size());
        assertEquals(Ast.BindingKind.VAL, foo.fields().get(1).bindingKind());
        assertTrue(foo.fields().get(1).mutableReferent());
        assertEquals(Ast.BindingKind.LET, foo.fields().get(2).bindingKind());
        assertTrue(foo.fields().get(2).mutableReferent());
    }

    @Test
    void constructorCanInitializeFixedSlotOnlyOnce() {
        String duplicate = """
                define class Foo as
                  val int value;
                  constructor() {
                    self.value = 1;
                    self.value = 2;
                  }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(duplicate)));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(duplicate)));
    }

    @Test
    void constructorRejectsConservativeCrossBranchSecondFixedWrite() {
        String ambiguous = """
                define class Foo as
                  val int value;
                  constructor() {
                    if true then
                      self.value = 1;
                    else
                      self.value = 2;
                    fi
                  }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(ambiguous)));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(ambiguous)));
    }

    @Test
    void finalReferentMutabilityIsIndependentFromSlotReassignment() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  final mut Array<int> items: [1, 2]
                  modify(mut self)(): void {
                    self.items[0] = 9;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  final Array<int> items: [1, 2]
                  modify(mut self)(): void {
                    self.items[0] = 9;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Foo as
                  final mut Array<int> items: [1, 2]
                  modify(mut self)(): void {
                    self.items = [9];
                  }
                end
                """)));
    }
}

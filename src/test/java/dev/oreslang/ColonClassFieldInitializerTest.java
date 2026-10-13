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
}

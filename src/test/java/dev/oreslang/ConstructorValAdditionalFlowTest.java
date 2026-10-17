package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConstructorValAdditionalFlowTest {
    private static void ok(String source) {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }
    private static void bad(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test void nestedBlockAndCondition() {
        ok("""
            define class C as
              val int x;
              constructor(int n) {
                if n > 0 then
                  { self.x = 1; }
                else
                  { self.x = 2; }
                fi
              }
            end
            """);
    }

    @Test void uninitializedReadInBranchCondition() {
        bad("""
            define class C as
              val int x;
              constructor() {
                if self.x > 0 then
                  self.x = 1;
                else
                  self.x = 2;
                fi
              }
            end
            """);
    }

    @Test void possibleRepeatAfterJoin() {
        bad("""
            define class C as
              val int x;
              constructor(bool a) {
                if a then self.x = 1; fi
                self.x = 2;
              }
            end
            """);
    }
    @Test void multiFieldOppositeInitializationOrder() {
        ok("""
            define class Pair as
              val int x;
              val int y;
              constructor(bool flag) {
                if flag then
                  self.x = 1;
                  self.y = 2;
                else
                  self.y = 3;
                  self.x = 4;
                fi
              }
            end
            """);
    }

    @Test void rejectsOneMissingFieldAcrossJoin() {
        bad("""
            define class Pair as
              val int x;
              val int y;
              constructor(bool flag) {
                if flag then
                  self.x = 1;
                  self.y = 2;
                else
                  self.x = 3;
                fi
              }
            end
            """);
    }

    @Test void returnsInsideOneInitializedBranchDoNotPolluteTheOther() {
        ok("""
            define class Pair as
              val int x;
              val int y;
              constructor(int n) {
                if n > 0 then
                  self.x = n;
                  self.y = n + 1;
                  return;
                fi
                self.y = 0;
                self.x = 0;
              }
            end
            """);
    }

    @Test void selfReferenceOnOwnRightHandSideIsRejected() {
        bad("""
            define class C as
              val int x;
              constructor() {
                self.x = self.x + 1;
              }
            end
            """);
    }

    @Test void constructorSelfCannotBeHiddenInArrayLiteral() {
        bad("""
            define class C as
              val int x;
              constructor() {
                val items = [self];
                self.x = 1;
              }
            end
            """);
    }

    @Test void selfCannotBePublishedEvenAfterValWasInitialized() {
        bad("""
            define class C as
              val int x;
              constructor() {
                self.x = 1;
                val items = [self];
              }
            end
            """);
    }

    @Test void lambdaCannotWriteInitializedVal() {
        bad("""
            define class C as
              val int x;
              constructor() {
                self.x = 1;
                val callback = || -> {
                  self.x = 2;
                  return;
                };
              }
            end
            """);
    }

    @Test void nestedBranchMissingInitializationStillRejects() {
        bad("""
            define class C as
              val int x;
              constructor(bool a, bool b) {
                if a then
                  if b then
                    self.x = 1;
                  fi
                else
                  self.x = 2;
                fi
              }
            end
            """);
    }

    @Test void nestedBranchAllPathsInitializeExactlyOnce() {
        ok("""
            define class C as
              val int x;
              constructor(bool a, bool b) {
                if a then
                  if b then
                    self.x = 1;
                  else
                    self.x = 2;
                  fi
                else
                  self.x = 3;
                fi
              }
            end
            """);
    }

    @Test void deterministicBranchInitializationMatrix() {
        java.util.Random random = new java.util.Random(20261017L);
        for (int i = 0; i < 128; i++) {
            int first = random.nextInt(1000);
            int second = random.nextInt(1000);
            int third = random.nextInt(1000);
            int fourth = random.nextInt(1000);
            String valid = """
                define class Matrix as
                  val int x;
                  val int y;
                  constructor(bool flag) {
                    if flag then
                      self.x = %d;
                      self.y = %d;
                    else
                      self.y = %d;
                      self.x = %d;
                    fi
                  }
                end
                """.formatted(first, second, third, fourth);
            ok(valid);

            String invalid = """
                define class Matrix as
                  val int x;
                  val int y;
                  constructor(bool flag) {
                    if flag then
                      self.x = %d;
                      self.y = %d;
                    else
                      self.x = %d;
                    fi
                  }
                end
                """.formatted(first, second, third);
            bad(invalid);
        }
    }

    @Test void rejectsPartialSelfEscapesWithoutAnyBlankValFields() {
        String[] classes = {
            """
            define class C as
              constructor() {
                val hidden = [self];
              }
            end
            """,
            """
            define class C as
              val int x = 7;
              constructor() {
                val hidden = [self];
              }
            end
            """,
            """
            define class C as
              let int x = 7;
              constructor() {
                val hidden = [self];
              }
            end
            """
        };
        for (String source : classes) {
            var model = Parser.parse(source);
            var klass = (dev.oreslang.ast.Ast.ClassDecl) model.modules()
                    .getFirst().declarations().getFirst();
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.types.ConstructorValInitialization.check(klass));
            assertTrue(failure.getMessage().contains("partially initialized self"),
                    failure.getMessage());
        }
    }

    @Test void directSelfFieldAccessRemainsAllowedWhenNoBlankValExists() {
        var program = Parser.parse("""
            define class C as
              let int x = 7;
              constructor() {
                self.x = 9;
              }
            end
            """);
        var klass = (dev.oreslang.ast.Ast.ClassDecl) program.modules()
                .getFirst().declarations().getFirst();
        assertDoesNotThrow(() ->
                dev.oreslang.types.ConstructorValInitialization.check(klass));
    }


    @Test void mutableClassFieldMayBeAssignedMoreThanOnce() {
        ok("""
            define class MutableValue as
              let int x;
              constructor(int n) {
                self.x = 1;
                self.x = n;
              }
            end
            """);
    }

    @Test void mutableClassFieldIsRequiredOnEveryNormalExit() {
        String source = """
            define class MutableValue as
              let int x;
              constructor(bool flag) {
                if flag then self.x = 1; fi
              }
            end
            """;
        var klass = (dev.oreslang.ast.Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.ConstructorValInitialization.check(klass));
        assertTrue(failure.getMessage().contains("did not initialize field 'x'"),
                failure.getMessage());
    }

    @Test void mutableFieldReadBeforeItsFirstAssignmentIsRejected() {
        String source = """
            define class MutableValue as
              let int x;
              constructor(int n) {
                let int y = self.x;
                self.x = n;
              }
            end
            """;
        var klass = (dev.oreslang.ast.Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.ConstructorValInitialization.check(klass));
        assertTrue(failure.getMessage().contains("read before constructor initialization"),
                failure.getMessage());
    }

    @Test void mixedMutableAndImmutableFieldsAreEachRequired() {
        ok("""
            define class Mixed as
              val int immutable;
              let int mutableValue;
              constructor(bool flag) {
                if flag then
                  self.immutable = 1;
                  self.mutableValue = 10;
                else
                  self.mutableValue = 20;
                  self.immutable = 2;
                fi
                self.mutableValue = 30;
              }
            end
            """);
    }

    @Test void mutableSecondWriteIsAllowedAfterConditionalJoinWhenDefinitelyInitialized() {
        ok("""
            define class MutableValue as
              let int x;
              constructor(bool flag) {
                if flag then self.x = 1; else self.x = 2; fi
                self.x = 3;
              }
            end
            """);
    }

    @Test void mutableFirstWriteHiddenInNestedExpressionCannotEstablishInitialization() {
        String source = """
            define class MutableValue as
              let int x;
              constructor() {
                val int y = (self.x = 1);
              }
            end
            """;
        var klass = (dev.oreslang.ast.Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.ConstructorValInitialization.check(klass));
    }


}

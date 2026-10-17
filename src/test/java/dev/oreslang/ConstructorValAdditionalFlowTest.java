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

}

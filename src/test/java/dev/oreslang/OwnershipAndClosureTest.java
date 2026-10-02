package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OwnershipAndClosureTest {

    @Test
    void lexicalClosureEscapesAndRetainsMutableCapturedState() throws Exception {
        String output = run("""
                fnc makeCounter() => (() -> int) {
                  let int count = 0;
                  return || -> {
                    count = count + 1;
                    return count;
                  };
                }

                pub routine main() => void {
                  val (() -> int) counter = makeCounter();
                  stdio.stdout.write(counter());
                  stdio.stdout.write(counter());
                  return;
                }
                """);
        assertEquals("12", output);
    }

    @Test
    void ordinaryParametersAreImmutableForFieldMutation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc change(Bar b) => void {
                          b.foo = "foobar";
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("immutable parameter/binding"));
    }

    @Test
    void ownedMutParameterMayMutateAndReturnOwnership() throws Exception {
        String output = run("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc change(Bar mut b) => Bar {
                  b.foo = "foobar";
                  return b;
                }

                pub routine main() => void {
                  let Bar b = new Bar();
                  let Bar changed = change(b);
                  stdio.stdout.write(changed.foo);
                  return;
                }
                """);
        assertEquals("foobar", output);
    }

    @Test
    void mutableBorrowAllowsMutationWithoutMovingOwner() throws Exception {
        String output = run("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc change(&mut Bar b) => void {
                  b.foo = "borrowed";
                  return;
                }

                pub routine main() => void {
                  let Bar b = new Bar();
                  change(&mut b);
                  stdio.stdout.write(b.foo);
                  return;
                }
                """);
        assertEquals("borrowed", output);
    }

    @Test
    void immutableBorrowBlocksOverlappingMutableBorrow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc mutate(&mut Bar b) => void {
                          b.foo = "changed";
                          return;
                        }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val &Bar read = &b;
                          mutate(&mut b);
                          stdio.println(read.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void useAfterMoveIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          consume(b);
                          stdio.println(b.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void borrowOfLocalCannotEscapeFunction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad() => &Bar {
                          let Bar b = new Bar();
                          return &b;
                        }
                        """)));
        assertTrue(error.getMessage().contains("outlive its owner"));
    }

    @Test
    void borrowedParameterCanBeReturned() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc identity(&Bar b) => &Bar {
                  return b;
                }
                """)));
    }


    @Test
    void multipleImmutableBorrowsMayCoexist() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc ok() => void {
                  let Bar b = new Bar();
                  val &Bar first = &b;
                  val &Bar second = &b;
                  stdio.println(first.foo);
                  stdio.println(second.foo);
                  return;
                }
                """)));
    }

    @Test
    void secondMutableBorrowIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val &mut Bar first = &mut b;
                          val &mut Bar second = &mut b;
                          stdio.println(first.foo);
                          stdio.println(second.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void moveWhileBorrowedIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void { return; }

                        fnc bad() => void {
                          let Bar b = new Bar();
                          val &Bar read = &b;
                          consume(b);
                          stdio.println(read.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot move"));
    }

    @Test
    void lexicalScopeEndsStoredBorrow() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc mutate(&mut Bar b) => void {
                  b.foo = "changed";
                  return;
                }

                fnc ok() => void {
                  let Bar b = new Bar();
                  if true; do
                    val &Bar read = &b;
                    stdio.println(read.foo);
                  fi
                  mutate(&mut b);
                  return;
                }
                """)));
    }


    @Test
    void moveInBothIfBranchesIsAllowedButValueIsMovedAfterJoin() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc consume(Bar b) => void { return; }

                fnc ok(bool flag) => void {
                  let Bar b = new Bar();
                  if flag; do
                    consume(b);
                  else
                    consume(b);
                  fi
                  return;
                }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b) => void { return; }

                        fnc bad(bool flag) => void {
                          let Bar b = new Bar();
                          if flag; do
                            consume(b);
                          else
                            consume(b);
                          fi
                          stdio.println(b.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void immutableFieldStaysImmutableEvenThroughMutOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub val String foo = "start";
                        end

                        fnc bad(Bar mut b) => void {
                          b.foo = "changed";
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("field 'Bar.foo' is immutable"));
    }

    @Test
    void moveOnlyCaptureTransfersIntoClosure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();
                          val (() -> int) read = || -> {
                            return box.value;
                          };
                          stdio.println(box.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'box'"));
    }

    @Test
    void mutableStructReceiverRequiresMutableOwnerAtCallSite() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Counter {
                          pub let int count = 0;

                          pub bump(self &mut self)() => int {
                            self.count = self.count + 1;
                            return self.count;
                          }
                        }

                        fnc bad() => void {
                          val Counter counter = Counter {};
                          counter.bump();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("method 'bump' receiver"));
        assertTrue(error.getMessage().contains("immutable"));
    }

    @Test
    void mutableStructReceiverWorksThroughLetOwner() throws Exception {
        String output = run("""
                struct Counter {
                  pub let int count = 0;

                  pub bump(self &mut self)() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }
                }

                pub fnc main() => void {
                  let Counter counter = Counter {};
                  stdio.stdout.write(counter.bump());
                  stdio.stdout.write(counter.bump());
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void traitComposedMutableReceiverKeepsSameBorrowRuleOnStructs() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define trait Counting as
                          private let int count = 0;

                          pub bump(self &mut self)() => int {
                            self.count = self.count + 1;
                            return self.count;
                          }
                        end

                        struct Counter with Counting {
                        }

                        fnc bad() => void {
                          val Counter counter = Counter {};
                          counter.bump();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("method 'bump' receiver"));
    }

    @Test
    void mutableStructReceiverAllowsOwnedTemporary() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                struct Counter {
                  pub let int count = 0;

                  pub bump(self &mut self)() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }
                }

                fnc ok() => int {
                  return Counter {}.bump();
                }
                """)));
    }

    @Test
    void nestedMutableReceiverRequiresMutableRootOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Inner {
                          pub let int count = 0;

                          pub bump(self &mut self)() => int {
                            self.count = self.count + 1;
                            return self.count;
                          }
                        }

                        struct Outer {
                          pub let Inner inner = Inner {};
                        }

                        fnc bad() => void {
                          val Outer outer = Outer {};
                          outer.inner.bump();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable parameter/binding 'outer'"));
    }

    @Test
    void nestedMutableReceiverRequiresMutableProjectedField() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Inner {
                          pub let int count = 0;

                          pub bump(self &mut self)() => int {
                            self.count = self.count + 1;
                            return self.count;
                          }
                        }

                        struct Outer {
                          pub val Inner inner = Inner {};
                        }

                        fnc bad() => void {
                          let Outer outer = Outer {};
                          outer.inner.bump();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("Outer.inner"));
        assertTrue(error.getMessage().contains("immutable"));
    }

    @Test
    void nestedMutableReceiverWorksWhenRootAndProjectionAreMutable() throws Exception {
        String output = run("""
                struct Inner {
                  pub let int count = 0;

                  pub bump(self &mut self)() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }
                }

                struct Outer {
                  pub let Inner inner = Inner {};
                }

                pub fnc main() => void {
                  let Outer outer = Outer {};
                  stdio.stdout.write(outer.inner.bump());
                  stdio.stdout.write(outer.inner.bump());
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void primitiveStructIsImplicitlyCopyAndGetsIndependentStorage() throws Exception {
        String output = run("""
                struct Point {
                  pub let int x = 1;
                }

                pub fnc main() => void {
                  let Point a = Point {};
                  let Point b = a;
                  b.x = 2;
                  stdio.stdout.write(a.x);
                  stdio.stdout.write(b.x);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void copyStructPassedByValueDoesNotMoveCallerAndCalleeGetsIndependentValue() throws Exception {
        String output = run("""
                struct Point {
                  pub let int x = 1;
                }

                fnc bump(Point mut value) => Point {
                  value.x = value.x + 1;
                  return value;
                }

                pub fnc main() => void {
                  let Point original = Point {};
                  let Point changed = bump(original);
                  stdio.stdout.write(original.x);
                  stdio.stdout.write(changed.x);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void nestedCopyStructsAreRecursivelyIndependent() throws Exception {
        String output = run("""
                struct Inner {
                  pub let int count = 1;

                  pub bump(self &mut self)() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }
                }

                struct Outer {
                  pub let Inner inner = Inner {};
                }

                pub fnc main() => void {
                  let Outer a = Outer {};
                  let Outer b = a;
                  b.inner.bump();
                  stdio.stdout.write(a.inner.count);
                  stdio.stdout.write(b.inner.count);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void callableLocalStructAliasRetainsImplicitCopySemantics() throws Exception {
        String output = run("""
                pub fnc main() => void {
                  struct Point {
                    pub let int x = 1;
                  }
                  type P = Point;

                  let P a = Point {};
                  let P b = a;
                  b.x = 2;
                  stdio.stdout.write(a.x);
                  stdio.stdout.write(b.x);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void closureCapturesCopyStructByValueRatherThanAliasingOuterSlot() throws Exception {
        String output = run("""
                struct Point {
                  pub let int x = 1;
                }

                pub fnc main() => void {
                  let Point point = Point {};
                  val (() -> int) read = || -> {
                    return point.x;
                  };

                  point.x = 2;
                  stdio.stdout.write(read());
                  stdio.stdout.write(point.x);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void structContainingClassRemainsMoveOnly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end

                        struct Holder {
                          pub let Box box = new Box();
                        }

                        fnc consume(Holder value) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Holder holder = Holder {};
                          consume(holder);
                          stdio.println(holder.box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved value 'holder'"));
    }

    @Test
    void inferredGenericStructSpecializationDerivesCopy() throws Exception {
        String output = run("""
                struct Box<T> {
                  pub let T value;
                }

                pub fnc main() => void {
                  let first = Box<> { value = 1 };
                  let second = first;
                  second.value = 2;
                  stdio.stdout.write(first.value);
                  stdio.stdout.write(second.value);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void contextualObjectToStructBindingUsesStructCopySemantics() throws Exception {
        String output = run("""
                struct Point {
                  pub let int x;
                }

                pub fnc main() => void {
                  let Point first = obj{x: 1};
                  let Point second = first;
                  second.x = 2;
                  stdio.stdout.write(first.x);
                  stdio.stdout.write(second.x);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void inferredGenericStructWithClassPayloadRemainsMoveOnly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class BoxedValue as
                          pub let int value = 1;
                        end

                        struct Box<T> {
                          pub let T value;
                        }

                        fnc consume(Box<BoxedValue> value) => void {
                          return;
                        }

                        fnc bad() => void {
                          let holder = Box<> { value = new BoxedValue() };
                          consume(holder);
                          stdio.println(holder.value.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved value 'holder'"));
    }

    @Test
    void callableLocalStructParameterUsesHoistedCopySemantics() throws Exception {
        String output = run("""
                fnc sum(T value) => int {
                  struct T {
                    pub let int x;
                  }

                  let T copy = value;
                  copy.x = copy.x + 1;
                  return value.x + copy.x;
                }

                pub fnc main() => void {
                  stdio.stdout.write(sum(obj{x: 3}));
                  return;
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void escapedCallableLocalStructRetainsCopySemanticsAtCaller() throws Exception {
        String output = run("""
                fnc make() => T {
                  struct T {
                    pub let int x;
                  }
                  return T { x = 1 };
                }

                pub fnc main() => void {
                  let first = make();
                  let second = first;
                  second.x = 2;
                  stdio.stdout.write(first.x);
                  stdio.stdout.write(second.x);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void escapedCallableLocalStructStillEnforcesMutableReceiverOnValOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc make() => T {
                          struct T {
                            pub let int x = 1;

                            pub bump(self &mut self)() => int {
                              self.x = self.x + 1;
                              return self.x;
                            }
                          }
                          return T {};
                        }

                        fnc bad() => void {
                          val result = make();
                          result.bump();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable parameter/binding 'result'"));
    }

    @Test
    void escapedCallableLocalStructAllowsMutableReceiverOnOwnedTemporary() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc make() => T {
                  struct T {
                    pub let int x = 1;

                    pub bump(self &mut self)() => int {
                      self.x = self.x + 1;
                      return self.x;
                    }
                  }
                  return T {};
                }

                fnc ok() => int {
                  return make().bump();
                }
                """)));
    }

    @Test
    void sameStructSpecializationUsesValueEquality() throws Exception {
        String output = run("""
                struct Box<T> {
                  pub let T value;
                }

                pub fnc main() => void {
                  val Box<int> left = Box<int> { value = 7 };
                  val Box<int> same = Box<int> { value = 7 };
                  val Box<int> different = Box<int> { value = 8 };
                  stdio.stdout.write(left == same);
                  stdio.stdout.write(left == different);
                  return;
                }
                """);

        assertEquals("truefalse", output);
    }

    @Test
    void differentGenericStructSpecializationsCannotBeComparedForEquality() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Box<T> {
                          pub let T value;
                        }

                        fnc bad(Box<int> left, Box<string> right) => bool {
                          return left == right;
                        }
                        """)));

        assertTrue(error.getMessage().contains("same nominal struct specialization"));
    }

    @Test
    void differentNamedStructsWithSameShapeCannotBeComparedForEquality() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Left {
                          pub let int value;
                        }

                        struct Right {
                          pub let int value;
                        }

                        fnc bad(Left left, Right right) => bool {
                          return left == right;
                        }
                        """)));

        assertTrue(error.getMessage().contains("same nominal struct specialization"));
    }

    @Test
    void traitComposedPrimitiveStatePreservesStructCopy() throws Exception {
        String output = run("""
                define trait Counting as
                  private let int count = 1;

                  pub bump(self &mut self)() => int {
                    self.count = self.count + 1;
                    return self.count;
                  }

                  pub read() => int {
                    return self.count;
                  }
                end

                struct Counter with Counting {
                }

                pub fnc main() => void {
                  let Counter first = Counter {};
                  let Counter second = first;
                  second.bump();
                  stdio.stdout.write(first.read());
                  stdio.stdout.write(second.read());
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void traitComposedClassStateMakesStructMoveOnly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end

                        define trait HoldsBox as
                          private let Box box = new Box();

                          pub read() => int {
                            return self.box.value;
                          }
                        end

                        struct Holder with HoldsBox {
                        }

                        fnc consume(Holder value) => void {
                          return;
                        }

                        fnc bad() => void {
                          let Holder holder = Holder {};
                          consume(holder);
                          stdio.println(holder.read());
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved value 'holder'"));
    }

    @Test
    void tupleContainingCopyStructIsStillMoveOnlyUntilTupleValueRuntimeExists() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Point {
                          pub let int x = 1;
                        }

                        fnc bad() => void {
                          let pair = (Point {}, 7);
                          let moved = pair;
                          stdio.println(pair[1]);
                          stdio.println(moved[1]);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved value 'pair'"));
    }

    @Test
    void escapedLocalStructNestedFieldKeepsOwnershipIdentity() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc make() => Outer {
                          struct Inner {
                            pub let int count = 1;

                            pub bump(self &mut self)() => int {
                              self.count = self.count + 1;
                              return self.count;
                            }
                          }

                          struct Outer {
                            pub let Inner inner = Inner {};
                          }

                          return Outer {};
                        }

                        fnc bad() => void {
                          val outer = make();
                          outer.inner.bump();
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("immutable parameter/binding 'outer'"));
    }

    @Test
    void escapedLocalNestedCopyStructsRemainIndependent() throws Exception {
        String output = run("""
                fnc make() => Outer {
                  struct Inner {
                    pub let int count = 1;

                    pub bump(self &mut self)() => int {
                      self.count = self.count + 1;
                      return self.count;
                    }
                  }

                  struct Outer {
                    pub let Inner inner = Inner {};
                  }

                  return Outer {};
                }

                pub fnc main() => void {
                  let first = make();
                  let second = first;
                  second.inner.bump();
                  stdio.stdout.write(first.inner.count);
                  stdio.stdout.write(second.inner.count);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void copyStructMayReturnSelfByValueAsIndependentCopy() throws Exception {
        String output = run("""
                struct Point {
                  pub let int x = 1;

                  pub duplicate() => Point {
                    return self;
                  }
                }

                pub fnc main() => void {
                  let first = Point {};
                  let second = first.duplicate();
                  second.x = 2;
                  stdio.stdout.write(first.x);
                  stdio.stdout.write(second.x);
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void moveOnlyStructCannotReturnBorrowedSelfByValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end

                        struct Holder {
                          pub let Box box = new Box();

                          pub duplicate() => Holder {
                            return self;
                          }
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot move non-Copy struct 'self'"));
    }

    @Test
    void moveOnlyFieldCannotBeMovedOutOfLiveStructBinding() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 1;
                        end

                        struct Holder {
                          pub let Box box = new Box();
                        }

                        fnc bad() => Box {
                          let Holder holder = Holder {};
                          return holder.box;
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot move non-Copy field 'Holder.box'"));
    }

    @Test
    void moveOnlyFieldMayBeMovedOutOfOwnedTemporaryStruct() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 1;
                end

                struct Holder {
                  pub let Box box = new Box();
                }

                fnc make() => Holder {
                  return Holder {};
                }

                fnc take() => Box {
                  return make().box;
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "ownership.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
    @Test
    void ownershipCheckerInspectsMethodsInsideCallableLocalStructs() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc make() => T {
                          struct T {
                            pub bad() => &int {
                              let int local = 7;
                              return &local;
                            }
                          }
                          return T {};
                        }
                        """)));

        assertTrue(error.getMessage().contains("outlive its owner"));
    }
}

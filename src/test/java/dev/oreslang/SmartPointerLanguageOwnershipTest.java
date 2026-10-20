package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SmartPointerLanguageOwnershipTest {
    @Test
    void smartPointerFactoriesAndNonBorrowingMembersTypecheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  val box = Box.new(7);
                  val int value = box.take();
                  stdio.println(value);

                  val arc = Arc.new(11);
                  val arc2 = arc.clone();
                  val weak = arc.downgrade();
                  val Option<Arc<int>> upgraded = weak.upgrade();
                  val int strong = arc.strong_count();
                  val int weak_count = arc.weak_count();
                  val bool closed = arc.is_closed();
                  stdio.println(arc2.strong_count());
                  stdio.println(upgraded);
                  stdio.println(strong);
                  stdio.println(weak_count);
                  stdio.println(closed);

                  val rc = Rc.new(13);
                  val rc2 = rc.clone();
                  val RcWeak<int> rcweak = rc.downgrade();
                  val Option<Rc<int>> rcup = rcweak.upgrade();
                  stdio.println(rc2.strong_count());
                  stdio.println(rcup);

                  val cell = RefCell.new(17);
                  return;
                }
                """)));
    }

    @Test
    void boxTakeConsumesTheHandle() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val box = Box.new(7);
                          val int value = box.take();
                          stdio.println(value);
                          stdio.println(box.is_live());
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("use of moved value 'box'"),
                failure.getMessage());
    }

    @Test
    void boxTakeRejectsLiveBorrow() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val box = Box.new(7);
                          val &Box<int> view = rt borrow box;
                          val int value = box.take();
                          stdio.println(value);
                          stdio.println(view);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("cannot move 'box' while it is borrowed"),
                failure.getMessage());
    }

    @Test
    void rcWeakRemainsNonSendAcrossAsyncBoundary() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc bad(RcWeak<int> value): int {
                          return value.strong_count();
                        }
                        """)));
        assertTrue(failure.getMessage().contains("task-safe"), failure.getMessage());
    }

    @Test
    void localUserBoxClassStillShadowsBuiltinBoxFactoryAndMembers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box<T> as
                  pub val T value;

                  static fnc new(T value): Box<T> {
                    return new Box<T>(value);
                  }

                  pub marker(): int {
                    return 99;
                  }
                end

                pub routine main(): void {
                  val Box<int> box = Box.new(7);
                  val int marker = box.marker();
                  stdio.println(marker);
                  return;
                }
                """)));
    }

    @Test
    void unknownSmartPointerMembersFailClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val arc = Arc.new(7);
                          arc.get();
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("unknown Arc member"),
                failure.getMessage());
    }
}

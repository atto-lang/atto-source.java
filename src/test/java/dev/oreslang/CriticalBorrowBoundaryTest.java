package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-level regression tests for the critical ticket / loan boundary. */
final class CriticalBorrowBoundaryTest {
    @Test
    void synchronousCriticalMayBorrowTemporarilyWithoutLeakingIt() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class Bar as
                  pub let String foo = "start";
                end
                critical fnc inspect(&Bar b): int { return 17; }
                fnc caller(): int {
                  let Bar b = new Bar();
                  return inspect(rt borrow b);
                }
                """));
    }

    @Test
    void criticalReturnCannotExposeBorrowedReference() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define class Bar as
                      pub let String foo = "start";
                    end
                    critical fnc leak(&Bar b): &Bar { return b; }
                    """));
        assertTrue(failure.getMessage().contains("critical callable")
                && failure.getMessage().contains("borrow"), failure::getMessage);
    }

    @Test
    void criticalReturnCannotHideBorrowInGeneric() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define class Bar as
                      pub let String foo = "start";
                    end
                    critical fnc leak(&Bar b): Option<&Bar> { return None; }
                    """));
        assertTrue(failure.getMessage().contains("critical callable"), failure::getMessage);
    }

    @Test
    void suspendCriticalCannotCaptureBorrowedParameterAcrossMandatoryYield() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define class Bar as
                      pub let String foo = "start";
                    end
                    suspend critical fnc use_borrow(&Bar b): int { return 1; }
                    """));
        assertTrue(failure.getMessage().contains("suspending critical callable")
                && failure.getMessage().contains("borrowed"), failure::getMessage);
    }

    @Test
    void suspendCriticalMethodCannotCaptureBorrowedParameter() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define class Bar as
                      pub let String foo = "start";
                    end
                    define class Store as
                      suspend critical use_borrow(&Bar b): int { return 1; }
                    end
                    """));
        assertTrue(failure.getMessage().contains("suspending critical callable"), failure::getMessage);
    }

    @Test
    void suspensionInsidePlainCriticalIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    async fnc acquire(): int { return 3; }
                    critical fnc bad(): int { return await acquire(); }
                    """));
        assertTrue(failure.getMessage().contains("non-suspending critical callable"), failure::getMessage);
    }

    @Test
    void suspendCriticalRejectsCallerWithLiveLexicalBorrow() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    define class Bar as
                      pub let String foo = "start";
                    end
                    suspend critical fnc commit(int x): int { return x; }
                    async fnc caller(): int {
                      let Bar b = new Bar();
                      val &Bar view = rt borrow b;
                      val result = commit(3);
                      stdio.println(view.foo);
                      return result;
                    }
                    """));
        assertTrue(failure.getMessage().contains("borrow"), failure::getMessage);
    }

    @Test
    void ownedCriticalArgumentsAreAccepted() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                suspend critical fnc commit(int x): int { return x; }
                async fnc caller(): int { return commit(3); }
                """));
    }
}

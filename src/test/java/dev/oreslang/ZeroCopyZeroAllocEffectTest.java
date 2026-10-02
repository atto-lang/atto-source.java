package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ZeroCopyZeroAllocEffectTest {

    @Test
    void noAllocRejectsDirectConstruction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        @NoAlloc
                        fnc hot() => Buffer {
                          return new Buffer(1);
                        }
                        """)));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("new Buffer"));
    }

    @Test
    void noCopyRejectsCopyIntrinsic() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        struct Point {
                          x: int;
                        }

                        @NoCopy
                        fnc duplicate(Point p) => Point {
                          return copy(p);
                        }
                        """)));

        assertTrue(error.getMessage().contains("@NoCopy contract violated"));
        assertTrue(error.getMessage().contains("copy(...)"));
    }

    @Test
    void noAllocPropagatesTransitivelyAcrossKnownFunctions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;
                        end

                        fnc allocate() => Buffer {
                          return new Buffer(1);
                        }

                        @NoAlloc
                        fnc hot() => Buffer {
                          return allocate();
                        }
                        """)));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("call to function '__root__.allocate'"));
        assertTrue(error.getMessage().contains("new Buffer"));
    }

    @Test
    void borrowAndTakeAreZeroCopyZeroAllocOwnershipOperations() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Buffer as
                  pub let int value = 0;
                end

                @NoAlloc
                @NoCopy
                fnc inspect(&Buffer b) => int {
                  return b.value;
                }

                @NoAlloc
                @NoCopy
                fnc pipeline(Buffer b) => int {
                  return inspect(borrow(b));
                }

                @NoAlloc
                @NoCopy
                fnc consume(Buffer b) => int {
                  let Buffer moved = take(b);
                  return moved.value;
                }
                """)));
    }

    @Test
    void shareIsBothCopyingAndAllocating() {
        IllegalArgumentException allocError = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;

                          pub copy() => Buffer {
                            return new Buffer(self.value);
                          }
                        end

                        @NoAlloc
                        fnc snapshot(Buffer b) => &Buffer {
                          return share(b);
                        }
                        """)));
        assertTrue(allocError.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(allocError.getMessage().contains("share(...)"));

        IllegalArgumentException copyError = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;

                          pub copy() => Buffer {
                            return new Buffer(self.value);
                          }
                        end

                        @NoCopy
                        fnc snapshot(Buffer b) => &Buffer {
                          return share(b);
                        }
                        """)));
        assertTrue(copyError.getMessage().contains("@NoCopy contract violated"));
        assertTrue(copyError.getMessage().contains("share(...)"));
    }

    @Test
    void noAllocRejectsOpaqueCallsWhoseEffectsCannotBeProven() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        @NoAlloc
                        fnc hot() => void {
                          stdio.println(1);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("unverified call 'stdio.println(...)'"));
    }

    @Test
    void pureArithmeticSatisfiesBothContracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                @NoAlloc
                @NoCopy
                fnc sum(int a, int b) => int {
                  return a + b;
                }
                """)));
    }

    @Test
    void noAllocRejectsHeapLikeCollectionLiterals() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        @NoAlloc
                        fnc build() => Array<int> {
                          return arr[1, 2, 3];
                        }
                        """)));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("array/list literal"));
    }

    @Test
    void methodContractsAreChecked() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Buffer as
                          pub let int value = 0;

                          @NoAlloc
                          pub duplicate() => Buffer {
                            return new Buffer(self.value);
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("@NoAlloc contract violated"));
        assertTrue(error.getMessage().contains("method '__root__.Buffer.duplicate/0'"));
    }

    @Test
    void effectContractAnnotationsDoNotAcceptArguments() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        @NoAlloc<int>
                        fnc bad() => int {
                          return 1;
                        }
                        """)));

        assertTrue(error.getMessage().contains("@NoAlloc does not accept arguments"));
    }
}

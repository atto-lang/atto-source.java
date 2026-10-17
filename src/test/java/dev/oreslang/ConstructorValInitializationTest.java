package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ConstructorValInitializationTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }

    private static void rejected(String source, String diagnostic) {
        IllegalArgumentException typeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(typeFailure.getMessage().contains(diagnostic), typeFailure.getMessage());

        IllegalArgumentException ownershipFailure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(ownershipFailure.getMessage().contains(diagnostic), ownershipFailure.getMessage());
    }

    @Test
    void everyConstructorOverloadMayInitializeBlankValExactlyOnce() {
        accepted("""
                define class Counter as
                    val int count;
                    constructor() { self.count = 0; return; }
                    constructor(int n) { self.count = n; return; }
                end
                """);
    }

    @Test
    void ifElseWritesAreExclusiveRatherThanAConservativeConflict() {
        accepted("""
                define class Counter as
                    val int count;
                    constructor(int n) {
                        if n > 0 then
                            self.count = n;
                        else
                            self.count = 0;
                        fi
                        return;
                    }
                end
                """);
    }

    @Test
    void branchReturningAfterAssignmentDoesNotPolluteSurvivingPath() {
        accepted("""
                define class Counter as
                    val int count;
                    constructor(int n) {
                        if n > 0 then
                            self.count = n;
                            return;
                        fi
                        self.count = 0;
                        return;
                    }
                end
                """);
    }

    @Test
    void independentlyChecksEveryConstructor() {
        rejected("""
                define class Counter as
                    val int count;
                    constructor() { self.count = 0; return; }
                    constructor(int n) { return; }
                end
                """, "did not initialize field 'count'");
    }

    @Test
    void rejectsBlankValOnFallthrough() {
        rejected("""
                define class Counter as
                    val int count;
                    constructor(int n) {
                        if n > 0 then
                            self.count = n;
                        fi
                    }
                end
                """, "did not initialize field 'count'");
    }

    @Test
    void rejectsEarlyReturnBeforeInitialization() {
        rejected("""
                define class Counter as
                    val int count;
                    constructor(int n) {
                        if n == 0 then
                            return;
                        fi
                        self.count = n;
                    }
                end
                """, "did not initialize field 'count'");
    }

    @Test
    void rejectsTwoWritesAlongOnePath() {
        rejected("""
                define class Counter as
                    val int count;
                    constructor() {
                        self.count = 1;
                        self.count = 2;
                    }
                end
                """, "immutable");
    }

    @Test
    void rejectsPossibleSecondWriteAfterConditional() {
        rejected("""
                define class Counter as
                    val int count;
                    constructor(bool flag) {
                        if flag then
                            self.count = 1;
                        fi
                        self.count = 2;
                    }
                end
                """, "immutable");
    }

    @Test
    void rejectsReadingBlankValBeforeInitialization() {
        rejected("""
                define class Counter as
                    val int count;
                    constructor(int n) {
                        val int previous = self.count;
                        self.count = n;
                    }
                end
                """, "read before constructor initialization");
    }

    @Test
    void rejectsHiddenWritesFromDeferredClosures() {
        rejected("""
                define class Counter as
                    val int count;
                    constructor() {
                        val callback = || -> {
                            self.count = 42;
                            return;
                        };
                    }
                end
                """, "must be assigned directly");
    }

    @Test
    void initializedValCannotBeReassignedByConstructor() {
        String source = """
                define class Counter as
                    val int count = 3;
                    constructor() { self.count = 4; }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }
}

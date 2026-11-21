package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ConstructorValInitialization;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deterministic, opt-in source-migration gate. This test suite intentionally
 * does not change the historical constructor runtime binding policy yet.
 */
final class ConstructorExplicitReceiverMigrationTest {
    private static Ast.ClassDecl klass(String source) {
        return (Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
    }

    private static void strictReject(String source) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ConstructorValInitialization.checkExplicitReceiver(klass(source)));
        assertTrue(failure.getMessage().contains("without a leading self binder"),
                failure::getMessage);
    }

    @Test
    void strictRequiresReceiverBinderForDirectFieldAssignment() {
        String legacy = """
                define class Foo as
                    val int value;
                    constructor(int n) { self.value = n; }
                end
                """;
        // Migration mode is opt-in. Existing executable admission is unchanged.
        assertDoesNotThrow(() -> ConstructorValInitialization.check(klass(legacy)));
        strictReject(legacy);
    }

    @Test
    void strictRequiresReceiverBinderForFieldReads() {
        strictReject("""
                define class Foo as
                    val int value: 2;
                    constructor() { val int observed = self.value; }
                end
                """);
    }

    @Test
    void strictRejectsReceiverCaptureInLambda() {
        strictReject("""
                define class Foo as
                    val int value: 2;
                    constructor() {
                        val cb = || -> {
                            val int observed = self.value;
                            return;
                        };
                    }
                end
                """);
    }

    @Test
    void explicitSelfAllowsInitializedAndUninitializedFieldOperations() {
        assertDoesNotThrow(() -> ConstructorValInitialization.checkExplicitReceiver(klass("""
                define class Foo as
                    val int value;
                    constructor(self, int n) {
                        self.value = n;
                        val int observed = self.value;
                    }
                end
                """)));
    }

    @Test
    void wrapperWithoutSelfMayDelegateToExplicitSelfTerminal() {
        assertDoesNotThrow(() -> ConstructorValInitialization.checkExplicitReceiver(klass("""
                define class Foo as
                    val int value;
                    constructor() { constructor(42); }
                    constructor(self, int n) { self.value = n; }
                end
                """)));
    }

    @Test
    void onlyConstructorUsingSelfRequiresTheBinder() {
        assertDoesNotThrow(() -> ConstructorValInitialization.checkExplicitReceiver(klass("""
                define class Foo as
                    val int value;
                    constructor(self) { self.value = 42; }
                    constructor(int n) { constructor(); }
                end
                """)));
    }

    @Test
    void strictCannotBypassBindingThroughDelegateTail() {
        strictReject("""
                define class Foo as
                    val int value;
                    constructor() {
                        constructor(42);
                        val int observed = self.value;
                    }
                    constructor(self, int n) { self.value = n; }
                end
                """);
    }
}

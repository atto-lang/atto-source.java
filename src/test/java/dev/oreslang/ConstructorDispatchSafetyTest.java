package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ConstructorValInitialization;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Construction-scoped receiver is not an ordinary dispatchable object.
 * Compile-time checks must reject virtual dispatch even when every field is
 * initialized, because publication happens only after the constructor ends.
 */
final class ConstructorDispatchSafetyTest {
    private static Ast.Program parse(String source) { return Parser.parse(source); }

    private static void rejected(String source, String diagnostic) {
        Ast.Program ast = parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) ast.modules().getFirst().declarations().getFirst();
        IllegalArgumentException byFlow = assertThrows(
                IllegalArgumentException.class,
                () -> ConstructorValInitialization.check(klass));
        assertTrue(byFlow.getMessage().contains(diagnostic), byFlow::getMessage);

        IllegalArgumentException byTypes = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(parse(source)));
        assertTrue(byTypes.getMessage().contains(diagnostic), byTypes::getMessage);

        IllegalArgumentException byOwnership = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(parse(source)));
        assertTrue(byOwnership.getMessage().contains(diagnostic), byOwnership::getMessage);
    }

    @Test
    void constructorCannotInvokeInstanceMethodEvenAfterInitialization() {
        rejected("""
                define class Foo as
                    val int value;
                    constructor(self) {
                        self.value = 42;
                        self.inspect();
                    }
                    inspect(): int { return self.value; }
                end
                """, "cannot dispatch instance method 'inspect'");
    }

    @Test
    void constructorCannotInvokeMethodBeforeAnyBlankFieldIsRequired() {
        rejected("""
                define class Foo as
                    val int value: 42;
                    constructor(self) {
                        self.inspect();
                    }
                    inspect(): int { return self.value; }
                end
                """, "cannot dispatch instance method 'inspect'");
    }

    @Test
    void constructorCannotTakeBoundMethodReference() {
        rejected("""
                define class Foo as
                    val int value: 42;
                    constructor(self) {
                        val callback = self.inspect;
                    }
                    inspect(): int { return self.value; }
                end
                """, "cannot reference instance method 'inspect'");
    }

    @Test
    void constructorCannotHideDispatchInsideCallback() {
        rejected("""
                define class Foo as
                    val int value: 42;
                    constructor(self) {
                        val callback = || -> {
                            self.inspect();
                            return;
                        };
                    }
                    inspect(): int { return self.value; }
                end
                """, "cannot dispatch instance method 'inspect'");
    }

    @Test
    void constructorCannotDispatchFromDelegatingTail() {
        rejected("""
                define class Foo as
                    val int value;
                    constructor(self) {
                        constructor(42);
                        self.inspect();
                    }
                    constructor(self, int n) {
                        self.value = n;
                    }
                    inspect(): int { return self.value; }
                end
                """, "cannot dispatch instance method 'inspect'");
    }

    @Test
    void ordinaryFieldInitializationAndPostConstructionMethodsStillWork() {
        String source = """
                define class Foo as
                    val int value;
                    constructor(self, int n) {
                        self.value = n;
                    }
                    pub inspect(): int { return self.value; }
                end
                pub routine main(): void {
                    val foo = new Foo(42);
                    stdio.stdout.write(foo.inspect());
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(parse(source)));
        assertDoesNotThrow(() -> OwnershipChecker.check(parse(source)));
    }

    @Test
    void knownInitializedFieldProjectionRemainsValid() {
        String source = """
                define class Foo as
                    val int value: 42;
                    constructor(self) {
                        val int observed = self.value;
                        return;
                    }
                end
                """;
        assertDoesNotThrow(() -> ConstructorValInitialization.check(
                (Ast.ClassDecl) parse(source).modules().getFirst().declarations().getFirst()));
    }
}

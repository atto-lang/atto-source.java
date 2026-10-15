package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration regressions against current mainline: eager ordinary class
 * initializers do not inherit unchecked rt effects from pure-looking operands.
 */
final class MainlineClassFieldEffectGateTest {
    @Test
    void rejectRuntimeIntrinsicEvenWithLiteralOperand() {
        for (String operation : java.util.List.of("fail", "take", "borrow", "share", "copy")) {
            String source = """
                    define class Unsafe as
                      final int answer: rt %s 42;
                    end
                    """.formatted(operation);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)),
                    "unproven rt " + operation + " must not enter eager initialization");
            assertTrue(error.getMessage().contains("without a proven transitive effect bound"),
                    "unexpected rt " + operation + " diagnostic: " + error.getMessage());
        }
    }

    @Test
    void rejectNestedUnwindingEffect() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    define class Unsafe as
                      final int answer: 2 + (rt fail "stop");
                    end
                    """)));
        assertTrue(error.getMessage().contains("without a proven transitive effect bound"));
    }

    @Test
    void rejectPotentiallyThrowingEagerIndexing() {
        // The operands are literal-only, but evaluating the index may still
        // throw after a previous field has been installed. Construction
        // cleanup for that path is not yet proven.
        for (String initializer : java.util.List.of("[1, 2][9]", "1 + [1, 2][9]")) {
            String source = """
                    define class Unsafe as
                      final int first: 42;
                      final int second: %s;
                    end
                    """.formatted(initializer);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)));
            assertTrue(error.getMessage().contains("without a proven transitive effect bound"),
                    error.getMessage());
        }
    }

    @Test
    void rejectPotentiallyUnwindingArithmeticAndCastsDuringEagerInitialization() {
        // A division/remainder exception or checked-cast failure may unwind
        // after an earlier slot was installed. We do not yet have a proof of
        // partial-construction resource cleanup for these expressions.
        for (String declaration : java.util.List.of(
                "final float second: 1 / 0;",
                "final int second: 1 % 0;",
                "final int second: 42 as int;")) {
            String source = """
                    define class Unsafe as
                      final int first: 42;
                      %s
                    end
                    """.formatted(declaration);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)),
                    "eager initializer must fail closed: " + declaration);
            assertTrue(error.getMessage().contains("without a proven transitive effect bound"),
                    error.getMessage());
        }
    }

    @Test
    void rejectEagerModuleMemberResolutionWithoutEffectProof() {
        // A MemberExpr is not proved effect-free merely because its receiver
        // is a simple name; module export resolution may evaluate user code.
        String source = """
                define module Settings as
                  pub val int answer: 42;
                end
                define class Unsafe as
                  final int first: 1;
                  final int second: Settings.answer;
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void rejectNestedEagerMemberResolution() {
        String source = """
                define module Settings as
                  pub val int answer: 42;
                end
                define class Unsafe as
                  final int first: 1;
                  final int second: 1 + Settings.answer;
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void rejectOpaqueImportedNamespaceAsEagerValue() {
        // Imported value evaluation crosses source/module boundaries, and
        // may invoke a declaration initializer at the time of first access.
        String source = """
                import module Remote from './remote';
                define class Unsafe as
                  final int first: 1;
                  final int second: Remote;
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void scalarConstructorParameterMayShadowImportedNamespace() {
        // An imported runtime name is opaque, but an actual local constructor
        // parameter with the same spelling is an independently owned scalar.
        String source = """
                import module Remote from './remote';
                define class Safe as
                  pub final int answer: Remote + 1;
                  constructor(int Remote) { return; }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void rejectUnprovenLocalNamespaceValueWithoutLookupEffects() {
        // A value leaf does not become pure simply because its name was
        // declared in this source file rather than in an imported unit.
        String source = """
                define module Settings as
                  pub val int answer: 42;
                end
                define class Unsafe as
                  final int first: 1;
                  final int second: Settings;
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void rejectScalarConstructorParameterShadowingZeroArgumentHelper() {
        // Runtime call dispatch sees the local scalar before the global
        // function. A checker that authorizes by function name alone is
        // unsound, because evaluating forty() will call an int value.
        String source = """
                fnc forty(): int { return 40; }
                define class Unsafe as
                  final int first: 1;
                  final int second: forty();
                  constructor(int forty) { return; }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void rejectBorrowOperatorsDuringEagerInitialization() {
        String source = """
                define class Unsafe as
                  final int first: 1;
                  final int second: &seed;
                  constructor(int seed) { return; }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void retainScalarConstructorParameterArithmetic() {
        String source = """
                define class Safe as
                  pub final int answer: seed + 2;
                  constructor(int seed) { return; }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void rejectImportedArrayAliasInEagerBuiltinConstructionShortcut() {
        // The zero-argument Array exception is only for the built-in,
        // not a symbol imported from another compilation unit. Do not
        // assume its source-side constructor effects are known.
        String source = """
                import class Array from './collections';
                define class Unsafe as
                  final Array<int> first: [1];
                  final Array<int> second: new Array<int>();
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void retainUnshadowedBuiltinArrayConstruction() {
        String source = """
                define class Safe as
                  pub final Array<int> values: new Array<int>();
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void rejectStaticHelperWhenModuleShadowsClassName() {
        // Runtime NameExpr resolves module namespaces ahead of classes.
        // A literal-only static helper in a same-named class must not
        // authorize a module's potentially effectful callable.
        String source = """
                define module Utility as
                  pub fnc forty(): int { return 40; }
                end
                define class Utility as
                  pub static fnc forty(): int { return 40; }
                end
                define class Unsafe as
                  final int first: 1;
                  final int second: Utility.forty();
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"),
                failure.getMessage());
    }

    @Test
    void rejectUnknownTransitiveCall() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    fnc effectful(): int {
                      stdio.stdout.write("not during construction");
                      return 42;
                    }
                    define class Unsafe as
                      final int answer: effectful();
                    end
                    """)));
        assertTrue(error.getMessage().contains("without a proven transitive effect bound"));
    }

    @Test
    void sourceIdenticalNativeScalarConstructorGuestReturnsForty() throws Exception {
        // Must remain byte-identical to LLVM's
        // tests/fixtures/class-scalar-constructor-arithmetic-native.ores.
        String sourceText = """
                define class Pair as
                  pub final int a: start + 1;
                  pub final mut int b: start * 2;
                  constructor(int start) { return; }
                end
                pub fnc main(): int {
                  let Pair p = new Pair(13);
                  return p.a + p.b;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sourceText)));
        org.graalvm.polyglot.Source source = org.graalvm.polyglot.Source
                .newBuilder(OresLanguage.ID, sourceText, "scalar-constructor-parity.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (org.graalvm.polyglot.Context context =
                org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .build()) {
            assertEquals(40, context.eval(source).asInt());
        }
    }

    @Test
    void rejectConstructorDispatchOnUnpublishedSelf() {
        String source = """
                define class Unsafe as
                  val int value;
                  constructor(int input) {
                    self.publish();
                    self.value = input;
                  }
                  pub publish(): void {
                    stdio.stdout.write(self.value);
                    return;
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(
                "constructor cannot invoke methods on partially initialized self"),
                error.getMessage());
    }

    @Test
    void rejectConstructorSelfCopyAndShare() {
        for (String operation : java.util.List.of("copy", "share", "take")) {
            String source = """
                    define class Unsafe as
                      val int value;
                      constructor(int input) {
                        const leaked = rt %s self;
                        self.value = input;
                      }
                    end
                    """.formatted(operation);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(source)));
            assertTrue(error.getMessage().contains(
                    "constructor cannot apply runtime operation to partially initialized self"),
                    error.getMessage());
        }
    }

    @Test
    void rejectInitializerCaptureOfUnpublishedSelf() {
        String source = """
                define class Unsafe as
                  final int first: 1;
                  final int second: self.first + 1;
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test
    void admitVerifiedSynchronousScalarHelper() {
        String source = """
                fnc forty(): int {
                  return 40;
                }
                define class Safe as
                  pub final int answer: forty() + 2;
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void rejectTrappedCallableAsConstructorSafeHelper() {
        String source = """
                trap fnc forty(): int {
                  return 40;
                }
                define class Unsafe as
                  pub final int answer: forty();
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(error.getMessage().contains("without a proven transitive effect bound"),
                error.getMessage());
    }

    @Test
    void retainPlainScalarInitializer() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                    define class Safe as
                      pub final int answer: 40 + 2;
                    end
                    """)));
    }
}

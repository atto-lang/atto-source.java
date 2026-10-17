package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class AnyUnknownTernaryGuardTest {
    @Test
    void lowercaseAnyIsReservedAndIntegerGuardNarrowsTernaryTrueBranch() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc foo(any v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class any as
                end
                """));
    }

    @Test
    void unguardedAnyCannotFlowToConcreteType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc foo(any v): int {
                          return v;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("return value"));
    }

    @Test
    void unknownRequiresValidationBeforeAnyOrConcreteUse() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc foo(unknown v): any {
                          return v;
                        }
                        """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc foo(unknown v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                """)));
    }

    @Test
    void integerGuardExecutesUnderRuntimeAndEvaluatesOneBranch() throws Exception {
        String output = run("""
                fnc foo(any v): int {
                  return Integer.is_int(v) ? v : 0;
                }

                pub fnc main(): void {
                  stdio.stdout.write(foo(42));
                  stdio.stdout.write(foo("x"));
                  return;
                }
                """);

        assertEquals("420", output);
    }

    @Test
    void opaqueTypesCannotCrossAsyncBoundaryWithoutNarrowing() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub async fnc bad(any v): void {
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("async task boundary")
                || failure.getMessage().contains("task-safe")
                || failure.getMessage().contains("any/unknown"));
    }

    @Test
    void typeofValueComparisonIsNotAcceptedAsDynamicTypeTest() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc foo(any v): int {
                  return typeof v == int ? v : 0;
                }
                """));
    }

    @Test
    void opaqueTopNamesCannotBeShadowedOrUsedAsRuntimeTestTargets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Any as
                        end
                        """)));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        type Unknown = int;
                        """)));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(any value): bool {
                          return value is type any;
                        }
                        """)));
    }

    @Test
    void legacyCapitalizedSpellingsRemainCompatibleTypeAliases() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc a(Any v): int {
                  return Integer.is_int(v) ? v : 0;
                }

                pub fnc b(Unknown v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                """)));
    }

    @Test
    void callableValuesAreSubtypesOfAnyButErasedAnyIsNotInvocable() {
        var callable = new dev.oreslang.types.Types.Function(
                java.util.List.of(dev.oreslang.types.Types.Primitive.INT),
                dev.oreslang.types.Types.Primitive.INT);
        assertTrue(dev.oreslang.types.Types.isAssignable(
                callable, dev.oreslang.types.Types.Any.INSTANCE));
        assertFalse(dev.oreslang.types.Types.isAssignable(
                dev.oreslang.types.Types.Any.INSTANCE, callable));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(any callback): void {
                          callback(1);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("not directly callable"));
    }

    @Test
    void opaqueValuesHaveNoUncheckedMembers() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(any value): void {
                          value.length;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("no statically available members"));
    }

    @Test
    void channelPayloadCannotContainOpaqueTopType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(Channel<any> values): void {
                          return;
                        }
                        """)));
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(Channel<List<unknown>> values): void {
                          return;
                        }
                        """)));
    }

    @Test
    void opaqueUnionNormalizationDoesNotEraseBorrowOrCapabilityEvidence() {
        var any = dev.oreslang.types.Types.Any.INSTANCE;
        var opaque = dev.oreslang.types.Types.UnknownValue.INSTANCE;
        var integer = dev.oreslang.types.Types.Primitive.INT;
        var borrowed = new dev.oreslang.types.Types.Borrow(integer, false);
        var channel = new dev.oreslang.types.Types.Named(
                "Channel", java.util.List.of(integer));

        assertSame(any, dev.oreslang.types.Types.unionOf(any, integer));
        assertSame(opaque, dev.oreslang.types.Types.unionOf(opaque, any));
        assertInstanceOf(dev.oreslang.types.Types.Union.class,
                dev.oreslang.types.Types.unionOf(any, borrowed));
        assertInstanceOf(dev.oreslang.types.Types.Union.class,
                dev.oreslang.types.Types.unionOf(opaque, borrowed));
        assertInstanceOf(dev.oreslang.types.Types.Union.class,
                dev.oreslang.types.Types.unionOf(any, channel));
    }

    @Test
    void writableStructuralRecordMembersRemainInvariant() {
        var integer = dev.oreslang.types.Types.Primitive.INT;
        var any = dev.oreslang.types.Types.Any.INSTANCE;
        var writableInt = new dev.oreslang.types.Types.Record(
                java.util.Map.of("value", integer));
        var writableAny = new dev.oreslang.types.Types.Record(
                java.util.Map.of("value", any));
        var readonlyInt = new dev.oreslang.types.Types.Record(
                java.util.Map.of("value", integer), true);
        var readonlyAny = new dev.oreslang.types.Types.Record(
                java.util.Map.of("value", any), true);

        assertFalse(dev.oreslang.types.Types.isAssignable(writableInt, writableAny));
        assertTrue(dev.oreslang.types.Types.isAssignable(writableInt, readonlyAny));
        assertFalse(dev.oreslang.types.Types.isAssignable(readonlyInt, writableInt));
        assertTrue(dev.oreslang.types.Types.isAssignable(readonlyInt, readonlyAny));
    }

    @Test
    void heterogeneousTernaryProducesExplicitUnionInsteadOfInferenceHole() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc choose(bool flag): int | string {
                  return flag ? 7 : "seven";
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(bool flag): int {
                          return flag ? 7 : "seven";
                        }
                        """)));
    }

    @Test
    void ternaryEvaluatesOnlyTheSelectedBranch() throws Exception {
        String output = run("""
                fnc left(): int {
                  stdio.stdout.write("L");
                  return 1;
                }

                fnc right(): int {
                  stdio.stdout.write("R");
                  return 2;
                }

                pub fnc main(): void {
                  stdio.stdout.write(true ? left() : right());
                  stdio.stdout.write(false ? left() : right());
                  return;
                }
                """);

        assertEquals("L1R2", output);
    }

    @Test
    void userDeclaredIntegerNamespaceCannotForgeTrustedNarrowing() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module Integer
                          pub fnc is_int(any v): bool { return true; }
                        end
                        pub fnc bad(any v): int {
                          return Integer.is_int(v) ? v : 0;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc Integer(): int { return 1; }
                        pub fnc bad(any v): int {
                          return Integer.is_int(v) ? v : 0;
                        }
                        """)));
    }

    @Test
    void mutableBindingsDoNotGainGuardedTypeRefinement() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc bad(any v): int {
                          let any candidate = v;
                          return Integer.is_int(candidate) ? candidate : 0;
                        }
                        """)));
    }

    @Test
    void trustedGuardNeverLeaksToFalseOrUnprovenBooleanEdges() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc badFalse(any v): int {
                          return Integer.is_int(v) ? 0 : v;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc badOr(any v): int {
                          return Integer.is_int(v) || true ? v : 0;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc badNegated(any v): int {
                          return !Integer.is_int(v) ? v : 0;
                        }
                        """)));
    }

    @Test
    void explicitTernaryBindingsAndAndRefinementsAreOwnershipSafe() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc explicit(any v): int {
                  return v is type int narrowed ? narrowed + 1 : 0;
                }

                pub fnc positive(any v): int {
                  return Integer.is_int(v) && v > 0 ? v : 0;
                }

                pub fnc repeated(any v): int {
                  return Integer.is_int(v) && Integer.is_int(v) ? v : 0;
                }
                """)));
    }

    @Test
    void explicitTernaryBindingExistsAtRuntimeOnlyOnTrueEdge() throws Exception {
        String output = run("""
                fnc explicit(any v): int {
                  return v is type int narrowed ? narrowed + 1 : 0;
                }

                pub fnc main(): void {
                  stdio.stdout.write(explicit(4));
                  stdio.stdout.write(explicit("x"));
                  return;
                }
                """);

        assertEquals("50", output);
    }

    @Test
    void importedIntegerNamespaceCannotBeMistakenForCompilerIntrinsic() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import * as Integer from './dep';

                        pub fnc bad(any v): int {
                          return Integer.is_int(v) ? v : 0;
                        }
                        """)));
    }

    @Test
    void nestedTernaryIsRightAssociativeAndConditionsRequireBool() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc choose(bool a, bool b): int {
                  return a ? 1 : b ? 2 : 3;
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc wrong(): int {
                          return 1 ? 2 : 3;
                        }
                        """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "any-guard.ores")
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
}

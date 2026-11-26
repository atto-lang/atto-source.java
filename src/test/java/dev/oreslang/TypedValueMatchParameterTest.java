package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TypedValueMatchParameterTest {
    @Test
    void pipelineMatchReusesTypedValueMatchAndEvaluatesSubjectOnce() throws Exception {
        String guest = """
                fnc input(): int {
                    stdio.println(7);
                    return 42;
                }
                pub fnc main(): void {
                    val int n = input() |> match<int> over
                        on 42: int -> 42,
                        on _: int -> 0,
                    end;
                    stdio.println(n);
                    return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        var output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, guest, "piped-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("7\n42", output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void checkedUnwrapPipelineKeepsTrapInsideMatchSubject() {
        String guest = """
                fnc choose(Option<int> x): int {
                    return trap! x.unwrap() |> match<int> over
                        on Ok(n): int -> n,
                        on Err(): int -> 42,
                    end;
                }
                """;
        var parsed = Parser.parse(guest);
        var fn = (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
        var returned = (Ast.ReturnStmt) fn.body().getFirst();
        var match = assertInstanceOf(Ast.MatchExpr.class, returned.value());
        assertInstanceOf(Ast.TrapExpr.class, match.subject());
        var err = assertInstanceOf(Ast.ConstructorPattern.class, match.arms().get(1).pattern());
        assertEquals("Err", err.constructor());
        assertEquals(1, err.arguments().size());
        assertInstanceOf(Ast.WildcardPattern.class, err.arguments().getFirst());
        assertEquals("int", match.resultType().name());
        var checked = TypeChecker.check(parsed);
        OwnershipChecker.check(checked);
    }

    @Test
    void pipelineArmAnnotationCannotEraseIndependentTypeConstraint() {
        String valid = "fnc choose(int x): int { return x |> match<int> over "
                + "on 42: int -> 42, on _: int -> 0, end; }";
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(valid.replace("on 42: int", "on 42: string")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(valid.replace("match<int>", "match")));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace("-> 42", "-> true"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace("on _: int -> 0,", ""))));
    }

    @Test
    void strictAttoMixedNumericLiteralPatternStillRejects() {
        String source = "pub fnc main(): int { val int x = 1; return x |> match<int> over "
                + "on 1.0: int -> 1, on _: int -> 42, end; }";
        var failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheckStrictAtto(source));
        assertTrue(failure.getMessage().contains("literal pattern type"), failure::getMessage);
    }

    @Test
    void positionalAndNamedReturnTypeRemainArmLocal() throws Exception {
        String guest = """
            fnc value(bool selected): int {
                val int n = match<int> selected over
                    on true -> { return 41; }
                    on false -> { return 1; }
                end;
                return n + 1;
            }
            fnc named(bool selected): string {
                return match<ret=string> selected over {
                    on true { return "ok"; }
                    on false { return "no"; }
                };
            }
            pub fnc main(): void {
                stdio.println(value(true));
                stdio.println(value(false));
                stdio.println(named(true));
                return;
            }
            """;
        var parsed = Parser.parse(guest);
        var typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);

        var fn = (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
        var binding = (Ast.BindingStmt) fn.body().getFirst();
        // Ensure callers never accidentally lose the annotation during parsing.
        assertInstanceOf(Ast.MatchExpr.class, binding.initializer());
        assertEquals("int", ((Ast.MatchExpr) binding.initializer()).resultType().name());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, guest, "typed-value-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42\n2\nok",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void declaredReturnTypeRejectsIncompatibleArm() {
        String guest = """
            fnc bad(bool selected): int {
                return match<int> selected over
                    on true -> { return "bad"; }
                    on false -> { return 4; }
                end;
            }
            """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test
    void invalidReturnParametersAndVoidReturnFailClosed() {
        for (String parameter : new String[]{"int, string", "type=int", ""}) {
            String guest = "fnc bad(bool x): int { return match<" + parameter +
                    "> x over { on true { return 1; } on false { return 0; } }; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(guest));
        }
        String voidGuest = """
            fnc bad(bool x): int {
                return match<void> x over {
                    on true { return 1; }
                    on false { return 0; }
                };
            }
            """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(voidGuest)));
    }

    @Test
    void inferredMatchStillAcceptsHistoricalSyntax() {
        String guest = """
            fnc legacy(bool x): int {
                return match x over {
                    on true { return 42; }
                    on false { return 0; }
                };
            }
            """;
        TypeChecker.check(Parser.parse(guest));
    }

    @Test
    void positiveAndNegativeTypePatternsPartitionAUnion() throws Exception {
        String guest = """
            fnc discriminate(int | string v): int {
                return match<int> v over
                    on int as n -> { return 10; }
                    on !int as other -> { return 20; }
                end;
            }
            pub fnc main(): void {
                stdio.println(discriminate(5));
                stdio.println(discriminate("text"));
                return;
            }
            """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, guest, "typed-complement-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("10\n20", output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void nonReifiableNegativePatternsFailClosed() {
        String guest = """
            fnc bad(Option<int> v): int {
                return match<int> v over
                    on !Option<int> as other -> { return 1; }
                    on _ -> { return 2; }
                end;
            }
            """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }


    @Test
    void borrowedResultParameterMustNotEraseLoanCapability() {
        String guest = """
            fnc bad(bool x): int {
                return match<&int> x over {
                    on true { return 1; }
                    on false { return 2; }
                };
            }
            """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test
    void unresolvedNominalPatternCannotBecomeAnAlwaysTrueComplement() {
        for (String pattern : new String[]{"!UnresolvableGuestType", "UnresolvableGuestType as unknown"}) {
            String guest = "fnc bad(int n): int { return match<int> n over " +
                    "on " + pattern + " -> { return 1; } " +
                    "on _ -> { return 2; } end; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)));
        }
    }

    @Test
    void equivalentNumericLiteralArmsAreRejectedAsUnreachable() {
        for (String arms : new String[] {
                "on 1 -> { return 10; } on 1.0 -> { return 20; }",
                "on 1.0 -> { return 10; } on 1 -> { return 20; }",
                "on 2 -> { return 10; } on 2.0 -> { return 20; }"
        }) {
            String guest = "fnc unreachable(int n): int { return match<int> n over "
                    + arms + " on _ -> { return 0; } end; }";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)), arms);
        }
    }

    @Test
    void largeIntegersDoNotAliasRoundedFloatingPointLiterals() throws Exception {
        String guest = """
            fnc score(int n): int {
                return match<int> n over {
                    on 9007199254740992.0 { return 10; }
                    on 9007199254740993 { return 20; }
                    on _ { return 30; }
                };
            }
            pub fnc main(): void {
                stdio.println(score(9007199254740993));
                stdio.println(score(9007199254740992));
                stdio.println(9007199254740993 == 9007199254740992.0);
                return;
            }
            """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        var output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, guest, "numeric-literal-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        assertEquals("20\n10\nfalse",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void mixedNumericEqualityStillAcceptsExactlyEqualValues() throws Exception {
        String guest = """
            fnc score(int n): int {
                return match<int> n over {
                    on 1.0 { return 10; }
                    on _ { return 20; }
                };
            }
            pub fnc main(): void {
                stdio.println(score(1));
                stdio.println(score(2));
                return;
            }
            """;
        TypeChecker.check(Parser.parse(guest));
        var output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, guest, "numeric-mixed-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        assertEquals("10\n20",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }
}

package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Legacy bare matches are exclusive, unlike source-ordered match ... over.
 * Multiple explicit arms may not match the same input at runtime.
 */
final class ExclusiveAdtMatchSafetyTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejectsOverlap(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("overlapping exclusive match arms"),
                error.toString());
    }

    @Test
    void legacyBoolCasesAreStaticallyDisjoint() {
        accepts("""
                fnc classify(bool input): int {
                    match input
                        true -> { return 1; }
                        false -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void legacyNominalEnumCasesHaveDistinctTags() {
        accepts("""
                define enum State as
                    Waiting
                    Done
                end
                fnc classify(State input): int {
                    match input
                        State.Waiting -> { return 1; }
                        State.Done -> { return 2; }
                    end
                }
                """);
    }

    @Test
    void exclusiveOverlappingGuardedPatternsAreRejected() {
        rejectsOverlap("""
                fnc classify(bool input, bool condition): int {
                    match input
                        true when condition -> { return 1; }
                        true when condition -> { return 2; }
                        false -> { return 0; }
                        else -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void guardNamesFromDifferentTuplePositionsAreNotProofOfDisjointness() {
        // For input (5, 20) BOTH guards hold. Although both arms call their
        // binding 'x', each arm binds a different tuple component.
        rejectsOverlap("""
                fnc classify(Tuple[int, int] pair): int {
                    match pair;
                        (x, _) when x < 10 -> { return 1; }
                        (_, x) when x > 10 -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void freeVariableGuardsCannotProveExclusiveCasesDisjoint() {
        // Two comparisons of an outer name are not a snapshot proof:
        // suspendable guards can observe different outer values.
        rejectsOverlap("""
                fnc classify(bool input, int threshold): int {
                    match input
                        true when threshold < 0 -> { return 1; }
                        true when threshold >= 0 -> { return 2; }
                        false -> { return 0; }
                        else -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void sameTupleProjectionHasStableExclusiveGuardPartitions() {
        accepts("""
                fnc classify(Tuple[int, int] pair): int {
                    match pair
                        (x, _) when x < 0 -> { return 1; }
                        (x, _) when x >= 0 -> { return 2; }
                        else -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void IdenticalPatternsWithProvenDisjointGuardRangesCanCoexist() {
        accepts("""
                define enum State as
                    Count: int
                end
                fnc classify(State input): int {
                    match input
                        State.Count(x) when x < 0 -> { return 1; }
                        State.Count(x) when x >= 0 -> { return 2; }
                        else -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void canonicalOrderedMatchesAllowOverlappingGuardedArms() {
        accepts("""
                fnc classify(bool input, bool condition): int {
                    match input over
                        on true when condition -> { return 1; }
                        on true when condition -> { return 2; }
                        on false -> { return 0; }
                        on _ -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void wideGuardBoundsCannotBecomeDisjointThroughDoubleRounding() {
        // 9007199254740993 rounds to 9007199254740992 as double.
        // The guards overlap for the exact integer 9007199254740992.
        rejectsOverlap("""
                define enum State as
                    Count: int
                end
                fnc classify(State input): int {
                    match input
                        State.Count(x) when x < 9007199254740993 -> { return 1; }
                        State.Count(x) when x >= 9007199254740992 -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void ExactRepresentableGuardBoundRemainsProvablyDisjoint() {
        accepts("""
                define enum State as
                    Count: int
                end
                fnc classify(State input): int {
                    match input
                        State.Count(x) when x < 9007199254740992 -> { return 1; }
                        State.Count(x) when x >= 9007199254740992 -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void mixedIntegerAndFloatingLiteralsAreNotExclusive() {
        // Runtime treats 1L and 1.0 as numerically equal.
        rejectsOverlap("""
                fnc classify(int value, bool guard): int {
                    match value;
                        1 when guard -> { return 1; }
                        1.0 when guard -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void differentMixedNumericLiteralsRemainProvablyDisjoint() {
        accepts("""
                fnc classify(int value, bool guard): int {
                    match value;
                        1 when guard -> { return 1; }
                        2.0 when guard -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void orderedMatchRejectsNumericallyEquivalentDeadLiteralArm() {
        String source = """
                fnc classify(int value): int {
                    match value over
                        on 1 -> { return 1; }
                        on 1.0 -> { return 2; }
                        on _ -> { return 0; }
                    end
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("unreachable match arm"),
                error.toString());
    }

    @Test
    void guestRuntimeUsesFirstMatchingOrderedArmAndFallsThroughFalseGuards() throws Exception {
        String program = """
                fnc classify(bool value, bool gate): int {
                    match value over
                        on true when gate -> { return 1; }
                        on true when gate -> { return 2; }
                        on false -> { return 0; }
                        on _ -> { return 3; }
                    end
                }
                pub fnc main(): void {
                    stdio.println(classify(true, true));
                    stdio.println(classify(true, false));
                    stdio.println(classify(false, true));
                    return;
                }
                """;
        accepts(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID,
                program, "ordered-match-guard-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("1\n3\n0",
                output.toString(StandardCharsets.UTF_8)
                        .strip().replace("\r\n", "\n"));
    }

    @Test
    void suspendableLegacyMatchKeepsExclusiveSemanticsAcrossGuardsAndFallback() throws Exception {
        // Async main executes match arms through runMatchArms rather than the
        // ordinary synchronous statement evaluator. Both must implement the
        // same exhaustive, exclusive semantics and preserve the selected arm.
        String program = """
                pub async routine main(): void {
                    match true
                        true -> { stdio.println("true"); }
                        false -> { stdio.println("wrong"); }
                    end
                    match 5
                        x when x < 0 -> { stdio.println("negative"); }
                        x when x >= 0 -> { stdio.println("positive"); }
                        else -> { stdio.println("fallback"); }
                    end
                    match false
                        true -> { stdio.println("wrong"); }
                        else -> { stdio.println("fallback"); }
                    end
                    return;
                }
                """;
        accepts(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID,
                program, "exclusive-suspendable-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("true\npositive\nfallback",
                output.toString(StandardCharsets.UTF_8).strip()
                        .replace("\r\n", "\n"));
    }

    @Test
    void orderedMatchAwaitsGuardBeforeAdvancingToNextArm() throws Exception {
        // The first arm suspends on a real async future and eventually
        // returns false. The second guard returns true after another await.
        // The dispatcher must not speculatively execute either body or
        // finish the match twice.
        for (String prefix : new String[]{"", "do "}) {
            String program = """
                    async fnc decide(bool approved): bool {
                        return approved;
                    }
                    pub async routine main(): void {
                        %smatch true over
                            on true when await decide(false) -> {
                                stdio.println("wrong");
                            }
                            on true when await decide(true) -> {
                                stdio.println("chosen");
                            }
                            on _ -> { stdio.println("fallback"); }
                        end
                        stdio.println("after");
                        return;
                    }
                    """.formatted(prefix);
            accepts(program);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Source guest = Source.newBuilder(OresLanguage.ID,
                    program, "ordered-async-guard-resumption.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.eval(guest);
            }
            assertEquals("chosen\nafter",
                    output.toString(StandardCharsets.UTF_8)
                            .strip().replace("\r\n", "\n"), prefix);
        }
    }

    @Test
    void exclusiveMatchDoesNotTreatAwaitedBooleanGuardsAsDisjointProof() {
        rejectsOverlap("""
                async fnc decide(bool approved): bool {
                    return approved;
                }
                pub async routine main(): void {
                    match true
                        true when await decide(true) -> { stdio.println("first"); }
                        true when await decide(false) -> { stdio.println("second"); }
                        else -> { stdio.println("fallback"); }
                    end
                    return;
                }
                """);
    }

    @Test
    void maximumOrderedGuardMatrixDoesNotRecurseOnCompletedGuards() throws Exception {
        // 4095 guards that complete inline, then the 4096th fallback.
        // A recursive callback chain can overflow the Java stack even if
        // no source-level recursion exists.
        StringBuilder source = new StringBuilder(
                "pub async routine main(): void {\nmatch true over\n");
        for (int i = 0; i < 4095; i++) {
            source.append("on true when false -> { stdio.println(\"wrong\"); }\n");
        }
        source.append("on _ -> { stdio.println(\"tail\"); }\nend\nreturn;\n}");
        String program = source.toString();
        accepts(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID,
                program, "max-ordered-suspendable-guards.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("tail", output.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void maximumOrderedFailedPatternMatrixDoesNotRecurse() throws Exception {
        // 4095 immediately rejected patterns must not create 4095 host
        // frames before the final matching arm is reached.
        StringBuilder source = new StringBuilder(
                "pub async routine main(): void {\nmatch true over\n");
        for (int i = 0; i < 4095; i++) {
            source.append("on false when false -> { stdio.println(\"wrong\"); }\n");
        }
        source.append("on true -> { stdio.println(\"tail\"); }\nend\nreturn;\n}");
        String program = source.toString();
        accepts(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID,
                program, "max-ordered-suspendable-patterns.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("tail", output.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void maximumExclusiveArmMatrixSelectsExactlyOneBodyWithoutRecursion() throws Exception {
        // Legacy matching must scan all 4095 explicit disjoint alternatives
        // before running the unique matching body. The 4096th is fallback.
        // This exercises the exclusive path rather than only ordered matching.
        StringBuilder source = new StringBuilder(
                "pub async routine main(): void {\nmatch 1\n");
        source.append("1 -> { stdio.println(\"selected\"); }\n");
        for (int i = 2; i <= 4095; i++) {
            source.append(i).append(" -> { stdio.println(\"wrong\"); }\n");
        }
        source.append("else -> { stdio.println(\"fallback\"); }\nend\nreturn;\n}");
        String program = source.toString();
        accepts(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID,
                program, "max-exclusive-suspendable-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("selected", output.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void synchronousAndSuspendableExclusiveMatchesProduceSameResults() throws Exception {
        String matches = """
                match true
                    true -> { stdio.println("match"); }
                    false -> { stdio.println("wrong"); }
                end
                match 5
                    x when x < 0 -> { stdio.println("wrong"); }
                    x when x >= 0 -> { stdio.println("guard"); }
                    else -> { stdio.println("wrong"); }
                end
                match false
                    true -> { stdio.println("wrong"); }
                    else -> { stdio.println("fallback"); }
                end
                """;
        String expected = "match\nguard\nfallback";
        for (String declaration : new String[]{
                "pub fnc main(): void {", "pub async routine main(): void {"}) {
            String program = declaration + "\n" + matches + "\nreturn;\n}";
            accepts(program);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Source guest = Source.newBuilder(OresLanguage.ID,
                    program, "exclusive-sync-async-differential.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.eval(guest);
            }
            assertEquals(expected,
                    output.toString(StandardCharsets.UTF_8).strip()
                            .replace("\r\n", "\n"), declaration);
        }
    }

    @Test
    void exclusiveMatchesHaveAtMostOneFallbackArm() {
        String source = """
                fnc classify(bool input): int {
                    match input
                        true -> { return 1; }
                        else -> { return 0; }
                        else -> { return 2; }
                    end
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("multiple fallback"),
                failure.toString());
    }
}

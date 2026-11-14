package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RangeAndTernaryBlockExpressionTest {
    private static final Path FIXTURE = Path.of("tests", "conformance", "ternary_blocks_ranges.atto");

    @Test
    void parsesTypechecksAndExecutesAttoGuestFixture() throws Exception {
        String source = Files.readString(FIXTURE, StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("123|123|42|42|42", run(source));
    }

    @Test
    void suspendableValueBlockRetainsSelectedArmAndLexicalScope() throws Exception {
        String source = Files.readString(
                Path.of("tests", "conformance", "ternary_blocks_ranges_async.atto"),
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("42", run(source));
    }

    @Test
    void recognizesRangesWithoutChangingArgumentSpread() {
        assertTrue(new Lexer("1..3").scan().stream().anyMatch(t -> t.type() == Token.Type.RANGE_EXCLUSIVE));
        assertTrue(new Lexer("1..=3").scan().stream().anyMatch(t -> t.type() == Token.Type.RANGE_INCLUSIVE));
        assertTrue(new Lexer("...").scan().stream().anyMatch(t -> t.type() == Token.Type.ELLIPSIS));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(wrap("for id in 1..2..3 { }")));
    }

    @Test
    void inclusiveRangeDoesNotOverflowAtSignedMax() throws Exception {
        String source = wrap("for id in 9223372036854775807..=9223372036854775807 { stdio.stdout.write(id); }");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("9223372036854775807", run(source));
    }

    @Test
    void exclusiveRangeAtSameEndpointIsEmpty() throws Exception {
        String source = wrap("for id in 9..9 { stdio.stdout.write(id); }");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("", run(source));
    }

    @Test
    void rejectsBadTypesAndMissingTailValues() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("for id in 1..true { }"))));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap("val x = true ? { 1; } : { 2 };")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap("val x = true ? { } : { 2 };")));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val x: int = true ? { val hidden = 1; hidden } : { hidden };"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val x = 123 ? { 1 } : { 2 };"))));
    }

    @Test
    void implicitAndExplicitReturnTernaryArmsProduceIdenticalAstAndResults() throws Exception {
        String implicit = wrap("""
                for id in 1..4 {
                  val successors: Array<int> = id eq 3 ? {
                    []
                  } : {
                    [id + 1]
                  };
                  stdio.stdout.write(id);
                }
                """);
        String explicit = wrap("""
                for id in 1..4 {
                  val successors: Array<int> = id eq 3 ? {
                    return []
                  } : {
                    return [id + 1]
                  };
                  stdio.stdout.write(id);
                }
                """);
        // `return` is syntax sugar for the arm's final expression,
        // not an enclosing-callable ReturnStmt.
        assertEquals(Parser.parse(implicit), Parser.parse(explicit));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(implicit)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(explicit)));
        assertEquals("123", run(implicit));
        assertEquals("123", run(explicit));
    }

    @Test
    void armLocalReturnDoesNotExitEnclosingFunction() throws Exception {
        String source = wrap("""
                val choice: int = true ? {
                  val base = 40;
                  return base + 2;
                } : {
                  return 0;
                };
                stdio.stdout.write("after:");
                stdio.stdout.write(choice);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("after:42", run(source));
    }

    @Test
    void explicitReturnIsFinalAndNotAvailableInStandaloneBlocks() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap(
                        "val x = true ? { return 1; val y = 2; } : { 0 };")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap(
                        "val x = true ? { return; } : { 2 };")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap(
                        "val x: int = { val x = 1; return x; };")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap(
                        "val x: int = { val x = 1; x };")));
    }

    @Test
    void bareZeroArgTernaryThunksInvokeOnlySelectedBranch() throws Exception {
        String source = wrap("""
                val x: int = true ? || -> {
                  return 42;
                } : || -> {
                  stdio.stdout.write("UNSELECTED");
                  return 99;
                };
                stdio.stdout.write("after:");
                stdio.stdout.write(x);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("after:42", run(source));
    }

    @Test
    void typedImmediateLambdaCallsWorkInBothTernaryArms() throws Exception {
        String source = wrap("""
                for id in 1..4 {
                  val successors: Array<int> = id eq 3 ? (|int v| -> {
                    return []
                  })(3) : (|int g| -> {
                    return [id + 1]
                  })(5);
                  stdio.stdout.write(id);
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("123", run(source));
    }

    @Test
    void thunkAndTypedLambdaTernaryArraysHaveCorrectElements() throws Exception {
        String source = wrap("""
                for id in 1..4 {
                  val thunked: Array<int> = id eq 3 ? || -> {
                    return []
                  } : || -> {
                    return [id + 1]
                  };
                  val called: Array<int> = id eq 3 ? (|int v| -> {
                    return []
                  })(3) : (|int g| -> {
                    return [id + 1]
                  })(5);
                  stdio.stdout.write(thunked.size);
                  stdio.stdout.write(called.size);
                  stdio.stdout.write(":");
                  stdio.stdout.write(id eq 3 ? 0 : thunked[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(id eq 3 ? 0 : called[0]);
                  stdio.stdout.write("|");
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("11:2:2|11:3:3|00:0:0|", run(source));
    }

    @Test
    void ordinaryLambdaValuesStayLazyUnlessCalled() throws Exception {
        String source = wrap("""
                val f = || -> {
                  stdio.stdout.write("IMPLICIT_CALL_BUG");
                  return 7;
                };
                val chosen = true ? (|| -> { return 11; }) : (|| -> { return 12; });
                stdio.stdout.write(chosen());
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("11", run(source));
    }

    @Test
    void invokedLambdaArgumentsRemainCheckedAndUnselectedBranchIsNotEvaluated() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val x: int = true ? (|int v| -> { return v; })(\"not int\") : { 0 };"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val x: int = true ? (|int v| -> { return v; })() : { 0 };"))));
    }

    @Test
    void lambdaTerminalReturnTypeIsCheckedAfterLexicalPrefixes() {
        // A prefix statement must not turn a statically known String return
        // into Unknown, which would be accepted in an int-valued ternary.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("""
                        val value: int = true ? || -> {
                          val wrong = "string";
                          return wrong;
                        } : { return 42; };
                        """))));
    }

    @Test
    void immediateComplexLambdaRequiresAnExplicitReturnType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("""
                        val value: int = true ? (|int n| -> {
                          if n eq 3 then
                            return 42;
                          fi
                          return 0;
                        })(3) : { return 0; };
                        """))));
        assertTrue(failure.getMessage().contains("statically known return type"),
                failure::getMessage);
    }

    @Test
    void rejectsNestedBorrowEscapingTernaryArmInCollection() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("""
                        val Array<int> numbers = new Array<int>();
                        val escaped = true ? {
                          [rt borrow numbers]
                        } : {
                          [rt borrow numbers]
                        };
                        """))));
        assertTrue(failure.getMessage().contains("nested borrows"),
                failure::getMessage);
    }

    @Test
    void rejectsSuspensionInsideDirectlyInvokedTernaryThunks() {
        IllegalArgumentException unsafeThunk = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("""
                        val value: int = true ? || -> {
                          rt cooperate;
                          return 1;
                        } : { return 0; };
                        """))));
        assertTrue(unsafeThunk.getMessage().contains("immediately invoked lambda"),
                unsafeThunk::getMessage);

        IllegalArgumentException unsafeTypedCall = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("""
                        val value: int = true ? (|int n| -> {
                          rt cooperate;
                          return n;
                        })(42) : { return 0; };
                        """))));
        assertTrue(unsafeTypedCall.getMessage().contains("immediately invoked lambda"),
                unsafeTypedCall::getMessage);
    }

    @Test
    void nestedPureTernaryInsideImmediateLambdaRemainsAllowed() throws Exception {
        String source = wrap("""
                val x: int = true ? (|int n| -> {
                  return n eq 42 ? {
                    val offset = 1;
                    n + offset
                  } : {
                    return 0
                  };
                })(42) : {
                  return 0;
                };
                stdio.stdout.write(x);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("43", run(source));
    }

    @Test
    void rangeAllowsNegativeAscendingAndRejectsDescendingIterations() throws Exception {
        String source = wrap("""
                for id in -2..1 {
                  stdio.stdout.write(id);
                  stdio.stdout.write(",");
                }
                stdio.stdout.write("|");
                for id in 4..1 {
                  stdio.stdout.write("WRONG");
                }
                for id in 4..=1 {
                  stdio.stdout.write("WRONG");
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("-2,-1,0,|", run(source));
    }

    @Test
    void ternaryRetainsRightAssociativityAndLazySelectedBranch() throws Exception {
        String source = wrap("""
                val result: int = false ? { 1 } : true ? {
                  val factor = 7;
                  factor * 6
                } : { 3 };
                stdio.stdout.write(result);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("42", run(source));
    }

    @Test
    void valueIfIsDistinctFromStatementIfAndMatchesTernary() throws Exception {
        String source = wrap("""
                val first: int = if true then
                  5
                else
                  4
                fi;
                val second: int = if false then 5 else 4 fi;
                val ternary: int = true ? { return 5 } : { return 4 };
                stdio.stdout.write(first);
                stdio.stdout.write(second);
                stdio.stdout.write(ternary);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("545", run(source));
    }

    @Test
    void valueIfElifAndArmBlocksAreLazy() throws Exception {
        String source = wrap("""
                val n: int = 2;
                val result: int = if n eq 1 then
                  { stdio.stdout.write("wrong"); return 1 }
                elif n eq 2 then
                  { val x: int = 40; x + 2 }
                else
                  { stdio.stdout.write("wrong"); return 3 }
                fi;
                stdio.stdout.write(result);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("42", run(source));
    }

    @Test
    void bracedImperativeIfNoLongerRequiresFi() throws Exception {
        String source = wrap("""
                if true {
                  stdio.stdout.write("yes");
                } else {
                  stdio.stdout.write("wrong");
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("yes", run(source));
    }

    @Test
    void valueIfRejectsMissingElseOrNonBoolPredicate() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap("val v = if true then 5 fi;")));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("val v = if 1 then 5 else 4 fi;"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("val v = if true then 5 else false fi;"))));
    }

    @Test
    void nestedBracedIfDoesNotEatEnclosingKeywordFi() throws Exception {
        String source = wrap("""
                if true then
                  if true {
                    stdio.stdout.write("inner");
                  }
                fi
                stdio.stdout.write("outer");
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("innerouter", run(source));
    }

    @Test
    void keywordIfInsideBracedStatementHasIndependentDelimiters() throws Exception {
        String source = wrap("""
                if true {
                  if false then
                    stdio.stdout.write("wrong");
                  else
                    stdio.stdout.write("right");
                  fi
                } else {
                  stdio.stdout.write("wrong");
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("right", run(source));
    }

    @Test
    void standaloneBracedStatementFollowedByFiIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap("if true { stdio.stdout.write(1); } fi")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap("if true { } else { } fi")));
    }

    @Test
    void deeplyChainedValueIfIsRejectedBeforeAstConstruction() {
        StringBuilder source = new StringBuilder("val v: int = if false then 0 ");
        for (int i = 0; i < 130; i++) {
            source.append("elif false then 0 ");
        }
        source.append("else 42 fi;");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap(source.toString())));
        assertTrue(failure.getMessage().contains("128 conditional arms"), failure::getMessage);
    }

    @Test
    void valueIfIsAdmittedInsideOrdinaryTernaryArmAndFunctionCall() throws Exception {
        String source = wrap("""
                val direct: int = true ? { (if true then 40 else 0 fi) + 2 } : { 1 };
                val nested: int = if false then 9 else true ? { 42 } : { 7 } fi;
                stdio.stdout.write(direct);
                stdio.stdout.write(":");
                stdio.stdout.write(nested);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("42:42", run(source));
    }

    @Test
    void valueIfChecksInactiveArmAndForbidsLeakingBranchLocals() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val v: int = if true then 42 else undeclared fi;"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val v: int = if true then { val hidden: int = 42; hidden } else 1 fi; stdio.stdout.write(hidden);"))));
    }

    @Test
    void voidConditionalExpressionsFailBeforeGuestExecution() {
        String ternary = wrap("""
                true ? stdio.stdout.write("selected") : stdio.stdout.write("wrong");
                """);
        String expressionIf = wrap("""
                if true then stdio.stdout.write("selected")
                else stdio.stdout.write("wrong")
                fi;
                """);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(ternary)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(expressionIf)));
    }

    private static String wrap(String body) {
        return "pub routine main(): void { " + body + " }";
    }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "ternary-block-range.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorScalarCallMachine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-to-bytecode admission tests. These tests inspect actual Atto AST
 * rather than manufacturing host-only function descriptors; compiled guest
 * execution needs separate polyglot JUnit receipts.
 */
final class ActorScalarSourceLoweringTest {
    private static ActorScalarCallMachine.Program lower(String source, String root) {
        Ast.Program ast = Parser.parse(source);
        Map<String, Ast.FunctionDecl> functions = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : ast.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    functions.put(fn.name() + "#" + fn.parameters().size(), fn);
                }
            }
        }
        Ast.FunctionDecl entry = functions.get(root);
        assertNotNull(entry, "test parser did not produce the intended function");
        return ActorScalarSourceLowering.tryCompile(entry,
                (name, arity) -> functions.get(name + "#" + arity));
    }

    @Test void scalarLiteralFunctionLowers() {
        assertNotNull(lower("fnc value(): int { return 42; }", "value#0"));
    }
    @Test void scalarParameterArithmeticLowers() {
        var supported = lower("fnc plus(int n): int { return n + 2; }", "plus#1");
        assertNotNull(supported);
        assertEquals(2, supported.functions().getFirst().maxStack(),
                "scalar compiler must charge exact peak operand slots");

        // This source is valid ordinary scalar arithmetic, but a deeply
        // nested expression exceeds our bounded *subset* operand stack.
        // Compile-time admission must fall back before actor execution,
        // never discover the limit mid-guest-run and poison the actor.
        String expression = "1";
        for (int i = 0; i < 25; i++) {
            expression = "1 + (" + expression + ")";
        }
        assertNull(lower("fnc deep(): int { return " + expression + "; }",
                "deep#0"));
    }
    @Test void scalarMutableLocalLowers() {
        assertNotNull(lower("""
                fnc local(int n): int {
                  let int sum = n + 1;
                  sum = sum + 1;
                  return sum;
                }
                """, "local#1"));
    }
    @Test void exhaustiveConditionalReturnsLower() {
        assertNotNull(lower("""
                fnc choose(int n): int {
                  if n == 0; do
                    return 0;
                  else
                    return n;
                  fi
                }
                """, "choose#1"));
    }
    @Test void mutualScalarTailRecursionLowersWithoutRecursiveJavaEvaluatorCalls() {
        var code = lower("""
                fnc left(int n): int {
                  if n == 0; do return 0;
                  else return right(n - 1); fi
                }
                fnc right(int n): int {
                  if n == 0; do return 0;
                  else return left(n - 1); fi
                }
                """, "left#1");
        assertNotNull(code);
        assertEquals(2, code.functions().size());
        assertTrue(code.functions().stream().flatMap(f -> f.code().stream())
                .anyMatch(i -> i.op() == ActorScalarCallMachine.Op.TAIL_CALL));
    }
    @Test void canonicalNonTailRecursionLowersIntoRealCallInstructions() {
        var program = lower("""
                fnc sum(int n): int {
                  if n == 0; do return 0;
                  else return 1 + sum(n - 1); fi
                }
                """, "sum#1");
        assertNotNull(program);
        assertTrue(program.functions().getFirst().code().stream()
                .anyMatch(i -> i.op() == ActorScalarCallMachine.Op.CALL));
    }
    @Test void boolReturnIsExplicitLegacyFallback() {
        assertNull(lower("fnc flag(int n): bool { return n == 0; }", "flag#1"));
    }
    @Test void stringReferenceParametersAreNeverEncodedInScalarActorFrames() {
        assertNull(lower("fnc name(String s): String { return s; }", "name#1"));
    }
    @Test void routineCannotEnterRecursiveScalarFncDispatcher() {
        assertNull(lower("routine main(): int { return 42; }", "main#0"));
    }
    @Test void unsupportedDivisionUsesLegacyEvaluator() {
        assertNull(lower("fnc half(int n): int { return n / 2; }", "half#1"));
    }
    @Test void unsupportedCalleeRejectsWholeCallGraphBeforeAdmission() {
        assertNull(lower("""
                fnc supported(int n): int { return other(n); }
                fnc other(int n): int { return n / 2; }
                """, "supported#1"));
    }
    @Test void unresolvedDirectCallRejectsWithoutPartialGraph() {
        assertNull(lower("fnc noMatch(int n): int { return missing(n); }", "noMatch#1"));
    }
    @Test void nonExhaustiveBranchWithoutTerminalReturnRejects() {
        assertNull(lower("""
                fnc missing(int n): int {
                  if n == 0; do return 1; fi
                }
                """, "missing#1"));
    }
    @Test void dynamicOrObjectProducingExpressionDoesNotLower() {
        assertNull(lower("""
                fnc composite(int n): int {
                  return [n, n][0];
                }
                """, "composite#1"));
    }
    @Test void scalarFunctionWithRuntimeIOEffectDoesNotLower() {
        assertNull(lower("""
                fnc noisy(int n): int {
                  stdio.stdout.write("noise");
                  return n;
                }
                """, "noisy#1"));
    }
    @Test void nonTailDeepReturnPcInstructionImageContainsTerminalTrap() {
        var program = lower("""
                fnc recursive(int n): int {
                  if n == 0; do return 0;
                  else return 1 + recursive(n - 1); fi
                }
                """, "recursive#1");
        assertNotNull(program);
        var instructions = program.functions().getFirst().code();
        assertEquals(ActorScalarCallMachine.Op.TRAP,
                instructions.getLast().op());
    }    @Test void intReturnCannotAcceptBooleanLiteral() {
        assertNull(lower("fnc wrong(): int { return true; }", "wrong#0"));
    }
    @Test void intReturnCannotAcceptComparisonBoolean() {
        assertNull(lower("fnc wrong(): int { return 1 < 2; }", "wrong#0"));
    }
    @Test void arithmeticCannotImplicitlyConvertBooleanToInteger() {
        assertNull(lower("fnc wrong(): int { return 1 + true; }", "wrong#0"));
    }
    @Test void unaryMinusCannotImplicitlyConvertBooleanToInteger() {
        assertNull(lower("fnc wrong(): int { return -true; }", "wrong#0"));
    }
    @Test void conditionalRequiresExactBoolean() {
        assertNull(lower("""
                fnc wrong(int n): int {
                  if n; do return 1; else return 2; fi
                }
                """, "wrong#1"));
    }
    @Test void scalarIntLocalCannotInitializeFromBoolean() {
        assertNull(lower("fnc wrong(): int { let int x = true; return 0; }",
                "wrong#0"));
    }
    @Test void valScalarCannotBeReboundByIterativeLowering() {
        assertNull(lower("fnc wrong(): int { val int x = 1; x = 2; return x; }",
                "wrong#0"));
    }
    @Test void constScalarCannotBeReboundByIterativeLowering() {
        assertNull(lower("fnc wrong(): int { const int x = 1; x = 2; return x; }",
                "wrong#0"));
    }
    @Test void ordinaryScalarParameterCannotBeRebound() {
        assertNull(lower("fnc wrong(int x): int { x = 2; return x; }",
                "wrong#1"));
    }
    @Test void mutableScalarCannotBeReboundToBoolean() {
        assertNull(lower("fnc wrong(): int { let int x = 1; x = true; return x; }",
                "wrong#0"));
    }
    @Test void directScalarCallRejectsBooleanArgument() {
        assertNull(lower("""
                fnc callee(int n): int { return n; }
                fnc caller(): int { return callee(true); }
                """, "caller#0"));
    }
    @Test void equalityCannotCompareDifferentPrimitiveKinds() {
        assertNull(lower("fnc wrong(): int { if 1 == true; do return 1; else return 2; fi }",
                "wrong#0"));
    }
    @Test void booleanComparisonMayBeUsedAsConditionButNotIntReturn() {
        assertNotNull(lower("""
                fnc supported(): int {
                  if 2 <= 3; do return 7; else return 0; fi
                }
                """, "supported#0"));
        assertNull(lower("fnc wrong(): int { return 2 <= 3; }", "wrong#0"));
    }
    @Test void immutableScalarReadRemainsSupportedWithoutRebind() {
        assertNotNull(lower("fnc supported(): int { val int x = 9; return x; }",
                "supported#0"));
    }
    @Test void pureBooleanEqualityConditionRemainsTyped() {
        assertNotNull(lower("""
                fnc supported(): int {
                  if true == true; do return 1; else return 0; fi
                }
                """, "supported#0"));
    }

    @Test void unreachableBranchMustStillRejectUnresolvedCallGraph() {
        // The narrow AOT subset validates every branch before allocating an
        // actor frame. A constant true guard cannot hide an invalid callee.
        assertNull(lower("""
                fnc wrong(): int {
                  if true; do return 1;
                  else return missing(2); fi
                }
                """, "wrong#0"));
    }

    @Test void nestedScalarCallCannotSmuggleBooleanArgument() {
        assertNull(lower("""
                fnc identity(int n): int { return n; }
                fnc inner(int n): int { return identity(n); }
                fnc wrong(): int { return inner(1 < 2); }
                """, "wrong#0"));
    }

    @Test void syntheticJvmIntegerLiteralDoesNotImplicitlyBecomeAttoInt() {
        Ast.FunctionDecl original = null;
        for (Ast.ModuleDecl module : Parser.parse(
                "fnc literal(): int { return 42; }").modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && function.name().equals("literal")) original = function;
            }
        }
        assertNotNull(original);
        Ast.FunctionDecl function = new Ast.FunctionDecl(
                original.name(), original.kind(), original.visibility(),
                original.async(), original.suspends(), original.generator(),
                original.structural(), original.nonLexical(), original.trapped(),
                original.actorKind(), original.genericParameters(),
                original.parameters(), original.returnType(),
                original.annotations(),
                java.util.List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(Integer.valueOf(42)))),
                original.genericSpecs(), original.effects());
        assertNull(ActorScalarSourceLowering.tryCompile(
                function, (name, arity) -> null),
                "source int is canonical signed i64 Long; do not silently widen JVM Integer");
    }

}

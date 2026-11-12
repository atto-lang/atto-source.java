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

final class ValueBoundaryAuditTest {
    @Test
    void tupleIndexIsPostfixAndCommaStaysInsideTuple() throws Exception {
        assertEquals("53195", run("""
                fnc first(int a, int b): int { return a; }
                pub fnc main(): void {
                  stdio.stdout.write((5,3,1)[0]);
                  stdio.stdout.write((5,3,1)[1]);
                  stdio.stdout.write((5,3,1)[2]);
                  stdio.stdout.write(((5,3),(9,1))[1][0]);
                  stdio.stdout.write(first((5,3,1)[0], 7));
                  return;
                }
                """));
        var ast = Parser.parse("fnc f(): int { return (5,3,1)[0]; }");
        var fn = (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
        var returned = (Ast.ReturnStmt) fn.body().getFirst();
        assertInstanceOf(Ast.TupleExpr.class,
                assertInstanceOf(Ast.IndexExpr.class, returned.value()).receiver());
    }

    @Test
    void recordsDestructureByNameAndTupleSpreadFillsParameters() throws Exception {
        assertEquals("42742", run("""
                fnc pair(): [int,int] { return (19,23); }
                fnc record(): {left: int, right: int} {
                  return record{right: 23, left: 19};
                }
                fnc add(int a, int b): int { return a+b; }
                fnc recordArg({right: int, left: int} value): int {
                  const {right, left} = value;
                  return left+right;
                }
                pub fnc main(): void {
                  const [a,b] = pair();
                  stdio.stdout.write(add(a,b));
                  const [x,...rest] = (7,8,9);
                  stdio.stdout.write(x);
                  stdio.stdout.write(recordArg(record()));
                  return;
                }
                """));
        assertEquals("42", run("""
                fnc add(int a, int b): int { return a+b; }
                fnc pair(): [int,int] { return (19,23); }
                pub fnc main(): void {
                  stdio.stdout.write(add(...pair()));
                  return;
                }
                """));
    }

    @Test
    void dynamicTupleIndexAndGetPreserveAllPossibleSlotTypes() {
        check("fnc f(int i): int | string { return (5,\"x\")[i]; }");
        reject("fnc f(int i): int { return (5,\"x\")[i]; }");
        reject("fnc f(int i): int { return (5,\"x\").get(i); }");
        check("fnc f(int i): int { return (5,3,1)[i]; }");
        reject("fnc f(): int { return (5,3,1)[-1]; }");
        reject("fnc f(): int { return (5,3,1)[3]; }");
        reject("fnc f(): int { return (5,3,1)[0.5]; }");
        reject("fnc f(): int { return [5,3,1][true]; }");
    }

    @Test
    void dynamicRecordKeyPreservesAllPossibleMemberTypes() {
        check("fnc f(string k): int | string { return infer struct{a: 5,b: \"x\"}[k]; }");
        reject("fnc f(string k): int { return infer struct{a: 5,b: \"x\"}[k]; }");
    }

    @Test
    void stringsRemainLiteralAndUnsupportedSurfacesFailClosed() throws Exception {
        assertEquals("${value} $value {value}", run("""
                pub fnc main(): void {
                  stdio.stdout.write("${value} $value {value}");
                  return;
                }
                """));
        reject("fnc f(): string { return `hello ${value}`; }");
        reject("fnc add(int a,int b):int{return a+b;} fnc f():int{return add.call((1,2));}");
        reject("fnc add(int a,int b):int{return a+b;} fnc f(List<int> x):int{return add(...x);}");
        reject("fnc f():int { if 1 { return 1; fi return 0; }");
    }

    @Test
    void bindingEvaluatesOnceHasLocalScopeAndAllowsArbitraryPredicate() throws Exception {
        assertEquals("16", run("""
                fnc produce(): int { stdio.stdout.write(1); return 6; }
                pub fnc main(): void {
                  if produce() as let value when value > 3 && value < 9 {
                    stdio.stdout.write(value);
                  fi
                  return;
                }
                """));
        assertEquals("false", run("""
                pub fnc main(): void {
                  if (false as let value when value eq true) {
                    stdio.stdout.write(true);
                  } else { stdio.stdout.write(value); fi
                  return;
                }
                """));
        reject("fnc f():int { if 1 as let v when true {fi return v; }");
        reject("fnc f():int { if 1 as let v when v {fi return 0; }");
        reject("fnc f():int { if 1 as let v {fi return 0; }");
    }

    @Test
    void laterBranchInitializersAreLazyAndKeywordFormWorks() throws Exception {
        assertEquals("142", run("""
                fnc produce(): int { stdio.stdout.write(4); return 2; }
                pub fnc main(): void {
                  if true { stdio.stdout.write(1); }
                  elif produce() as let v when v eq 2 { stdio.stdout.write(9); fi
                  if false; then stdio.stdout.write(0);
                  elif produce() as let v when v > 0; then stdio.stdout.write(v);
                  fi
                  return;
                }
                """));
    }

    @Test
    void whileReevaluatesAndRebindsAfterContinueAndStopsOnFalsePredicate() throws Exception {
        assertEquals("314", run("""
                pub fnc main(): void {
                  let n = 4;
                  while n as let value when value > 0 {
                    n = n - 1;
                    if value eq 2 { continue; fi
                    if value eq 4 { continue; fi
                    stdio.stdout.write(value);
                  }
                  stdio.stdout.write(4);
                  return;
                }
                """));
        reject("fnc f():int { while (1 as let v when false) {} return v; }");
    }

    @Test
    void shiftsAndCheckedCastsKeepTheirExistingMeaning() throws Exception {
        assertEquals("21", run("""
                pub fnc main(): void {
                  if (8 >> 1) eq 4 { stdio.stdout.write(2); fi
                  if (1 as int) eq 1 { stdio.stdout.write(1); fi
                  return;
                }
                """));
    }

    @Test
    void boundValuesSupportSuspensionBreakAndLexicalShadowing() throws Exception {
        assertEquals("37", run("""
                async fnc next(int n): int { return n - 1; }
                pub async fnc main(): void {
                  let n = 4;
                  while (await next(n) as let value when value > 0) {
                    n = value;
                    if value eq 2 { break; fi
                    stdio.stdout.write(value);
                  }
                  const value = 7;
                  stdio.stdout.write(value);
                  return;
                }
                """));
        assertEquals("3", run("""
                fnc pick(): int {
                  if 3 as let v when v > 0 { return v; }
                  else { return 0; fi
                }
                pub fnc main(): void { stdio.stdout.write(pick()); return; }
                """));
    }

    @Test
    void suspendableConjunctionUsesLeftPatternBindingsOnTheRight() throws Exception {
        // The right operand must evaluate in the left operand's scoped
        // environment even when the left predicate awaits a Future.
        assertEquals("7", run("""
                async fnc ready(): Option<int> { return Some(7); }
                pub async fnc main(): void {
                  if ((await ready()) matches Some(value)) && value > 3 {
                    stdio.stdout.write(value);
                  } fi
                  return;
                }
                """));
    }

    @Test
    void conditionBindingSnapshotPreservesNullAndDeclarationOrder() throws Exception {
        // Directly exercise nullable host/guest values; Map.copyOf silently
        // turns these otherwise valid bindings into a NullPointerException.
        Class<?> evaluator = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator");
        var method = evaluator.getDeclaredMethod("immutableBindings", java.util.Map.class);
        method.setAccessible(true);
        var original = new java.util.LinkedHashMap<String, Object>();
        original.put("nullable", null);
        original.put("last", 7);
        @SuppressWarnings("unchecked")
        var snapshot = (java.util.Map<String, Object>) method.invoke(null, original);
        original.put("last", 99);
        assertEquals(java.util.List.of("nullable", "last"),
                java.util.List.copyOf(snapshot.keySet()));
        assertTrue(snapshot.containsKey("nullable"));
        assertNull(snapshot.get("nullable"));
        assertEquals(7, snapshot.get("last"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("new", 1));
    }

    @Test
    void hostIndicesCannotSilentlyTruncateFloatsAtRuntime() {
        var policy = dev.oreslang.runtime.IsolatePolicy.developer().withCapabilities(
                dev.oreslang.runtime.IsolatePolicy.Capability.JAVA_INTEROP);
        for (String operation : new String[]{"values[index]", "values.get(index)",
                "values.set(index, 9)", "values[index] = 9"}) {
            String program = """
                    import fnc sqrt as jsqrt from "java:java.lang.Math";
                    pub fnc main(): void {
                      let mut values = [5,3];
                      const index = jsqrt(0.25);
                      %s;
                      return;
                    }
                    """.formatted(operation);
            check(program);
            try (var context = policy.restrictedContextBuilder(
                    dev.oreslang.runtime.ExecutionProfile.serverJit(),
                    java.util.Set.of("java.lang.Math")).build()) {
                var error = assertThrows(org.graalvm.polyglot.PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, program));
                assertTrue(error.getMessage().contains("index must be an integer"), error.getMessage());
            }
        }
    }

    @Test
    void whileTuplePostfixIsUnambiguousAndLegacyGroupedConditionsRemainValid() throws Exception {
        assertEquals("531", run("""
                pub fnc main(): void {
                  while (5,3,1)[0] eq 5 { stdio.stdout.write(5); break; }
                  let n = 1;
                  while (n) > 0 { stdio.stdout.write(3); n = n-1; }
                  while (true, true) { stdio.stdout.write(1); break; }
                  while (0) as let value when value > 0 { stdio.stdout.write(9); }
                  return;
                }
                """));
    }

    @Test
    void nestedLambdaBindingsDoNotBecomeTheOuterConditionHead() throws Exception {
        assertEquals("1", run("""
                pub fnc main(): void {
                  if (|| -> {
                    if 1 as let v when v eq 1 { return true; }
                    else { return false; fi
                  })() { stdio.stdout.write(1); fi
                  return;
                }
                """));
    }

    private static void check(String source) {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
    }

    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class, () -> check(source));
    }

    private static String run(String program) throws Exception {
        check(program);
        var output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, program, "value-boundaries.ores").build();
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class CaptureExpressionAuditTest {
    @Test
    void predicateArgumentEvaluatesOnceAndBindsTheOriginalValue() {
        assertEquals("p42", run("""
                fnc produce(): int { stdio.stdout.write("p"); return 42; }
                fnc is_ready(int value): bool { return value > 0; }
                pub fnc main(): void {
                  if is_ready(produce() as let value) { stdio.stdout.write(value); fi
                  return;
                }
                """));
    }

    @Test
    void comparisonsFalsePayloadAndMemberCallsKeepTheirValueSemantics() {
        assertEquals("5falseok", run("""
                pub fnc main(): void {
                  if (5 as let value) > 0 then stdio.stdout.write(value); fi
                  if (false as let flag) eq false { stdio.stdout.write(flag); fi
                  if ("ok" as let text).starts_with("o") { stdio.stdout.write(text); fi
                  return;
                }
                """));
    }

    @Test
    void capturesDoNotEscapeTheirBranchOrShadowAnOuterElseBinding() {
        assertEquals("79", run("""
                pub fnc main():void {
                  const value=7;
                  if (0 as let value) > 0 { stdio.stdout.write(value);
                  } else { stdio.stdout.write(value); fi
                  if false { stdio.stdout.write(0);
                  } elif (9 as let value) > 0 { stdio.stdout.write(value); fi
                  return;
                }
                """));
        reject("fnc f():int{if (1 as let value)>0 {return value; fi return value;}");
        reject("fnc f():int{if (1 as let value)>0 {return value;} else {return value; fi}");
        reject("fnc f():int{if (0 as let value)>0 {return value;} elif value>0 {return value;} else {return 0; fi}");
    }

    @Test
    void runtimeEvaluationOrderAndMultipleCapturesArePreserved() {
        assertEquals("12312", run("""
                fnc first():int{stdio.stdout.write(1);return 1;}
                fnc second():int{stdio.stdout.write(2);return 2;}
                fnc third():int{stdio.stdout.write(3);return 3;}
                fnc ready(int a,int b,int c):bool{return a+b eq c;}
                pub fnc main():void {
                  if ready(first() as let a, second() as let b, third()) {
                    stdio.stdout.write(a); stdio.stdout.write(b);
                  fi
                  return;
                }
                """));
    }

    @Test
    void loopCapturesAreFreshAcrossContinueAndBreak() {
        assertEquals("31", run("""
                fnc ready(int value):bool{return value>0;}
                pub fnc main():void {
                  let n=3;
                  while ready(n as let value) {
                    n=n-1;
                    if value eq 2 {continue;fi
                    stdio.stdout.write(value);
                    if value eq 1 {break;fi
                  }
                  return;
                }
                """));
    }

    @Test
    void awaitedInitializerAndAwaitedPredicatePreserveTheBinding() {
        assertEquals("42", run("""
                async fnc produce():int{return 42;}
                async fnc ready(int value):bool{return value>0;}
                pub async fnc main():void {
                  if await ready((await produce()) as let value) {
                    stdio.stdout.write(value);
                  fi
                  return;
                }
                """));
    }

    @Test
    void nonbooleanPredicatesAndSkippedOrEscapingCapturesAreRejected() {
        reject("fnc f():void{if (1 as let value) {return;fi}");
        reject("fnc f():void{const x=(1 as let value);return;}");
        reject("fnc f():void{if true || (1 as let value)>0 {stdio.stdout.write(value);fi}");
        reject("fnc f():void{if true && (1 as let value)>0 {stdio.stdout.write(value);fi}");
        reject("fnc f():void{if (true ? (1 as let value) : 0)>0 {return;fi}");
        reject("fnc f():void{if ((1 as let value)+(2 as let value))>0 {return;fi}");
    }

    @Test
    void consumingTheCapturedOwnerCannotLeaveAReusableAlias() {
        rejectOwnership("""
                define class Box as end
                fnc ready(Box value):bool{return true;}
                pub fnc main():void {
                  if ready(new Box() as let value) { ready(value); fi
                  return;
                }
                """);
    }

    @Test
    void capturesCanBeReboundWithoutGrantingReferentMutability() {
        assertEquals("6", run("""
                pub fnc main():void {
                  if (5 as let value)>0 {value=6;stdio.stdout.write(value);fi
                  return;
                }
                """));
        reject("fnc f():void{if (infer struct{x:1} as let v).x>0 {v.x=2;fi return;}");
    }

    @Test
    void sharedPredicateLoanEndsBeforeBodyConsumesTheCapturedOwner() {
        assertEquals("ok", run("""
                define class Box as end
                fnc ready(borrow Box value):bool{return true;}
                fnc consume(take! Box value):void{stdio.stdout.write("ok");return;}
                pub fnc main():void {
                  if ready(rt borrow (new Box() as let value)) {consume(value);fi
                  return;
                }
                """));
    }

    @Test
    void retainedBorrowCaptureKeepsTheOriginalOwnersLease() {
        assertEquals("ok", run("""
                define class Box as end
                fnc ready(borrow Box value):bool{return true;}
                fnc consume(take! Box value):void{stdio.stdout.write("ok");return;}
                pub fnc main():void {
                  val Box owner=new Box();
                  if ready((rt borrow owner) as let view) {ready(view);fi
                  consume(owner);
                  return;
                }
                """));
        rejectOwnership("""
                define class Box as end
                fnc ready(borrow Box value):bool{return true;}
                fnc consume(take! Box value):void{return;}
                fnc f():void {
                  val Box owner=new Box();
                  if ready((rt borrow owner) as let view) {consume(owner);fi
                  return;
                }
                """);
        rejectOwnership("""
                define class Box as end
                fnc ready(borrow Box value):bool{return true;}
                async fnc pause():int{return 1;}
                async fnc f():void {
                  val Box owner=new Box();
                  if ready((rt borrow owner) as let view) {await pause();fi
                  return;
                }
                """);
    }

    @Test
    void capturesCannotCreateMutableCapabilitiesOrHideAliasedLoans() {
        rejectOwnership("""
                define class Box as end
                fnc ready(borrow mut Box a):bool{return true;}
                fnc f():void {
                  if ready(rt mut borrow (new Box() as let value)) {return;fi
                }
                """);
        rejectOwnership("""
                define class Box as end
                fnc ready(borrow Box a,borrow mut Box b):bool{return true;}
                fnc f():void {
                  val Box owner=new Box();
                  if ready((rt borrow owner) as let view, rt mut borrow owner) {return;fi
                }
                """);
        rejectOwnership("""
                define class Box as end
                fnc ready(borrow Box a):bool{return true;}
                fnc f(borrow Box fallback):borrow Box {
                  if ready((rt borrow (new Box() as let owner)) as let view) {return view;
                  } else {return fallback;fi
                }
                """);
    }

    @Test
    void refutablePatternsCheckTheVariantRatherThanPayloadTruthiness() {
        assertEquals("false02", run("""
                fnc maybe():Option<int>{return None;}
                pub fnc main():void {
                  if let Some(flag)=Some(false) then stdio.stdout.write(flag);fi
                  if let Some(n)=Some(0) {stdio.stdout.write(n);fi
                  if let Some(n)=maybe() {stdio.stdout.write(9);
                  } else {stdio.stdout.write(2);fi
                  return;
                }
                """));
        reject("fnc f():void{if let value=1 then return;fi}");
        reject("fnc f():void{if let _=1 then return;fi}");
        reject("fnc f():void{if let Some(n)=Some(1) then return;fi stdio.stdout.write(n);}");
    }

    @Test
    void patternGuardsAndLoopsRetainBindingsAcrossSuspension() {
        assertEquals("123", run("""
                async fnc ready(int n):bool{return n>0;}
                pub async fnc main():void {
                  if let Some(n)=Some(1) && await ready(n) {stdio.stdout.write(n);fi
                  let n=2;
                  while let Some(value)=(n<4 ? Some(n) : None) {
                    stdio.stdout.write(value);n=n+1;
                  }
                  return;
                }
                """));
    }

    @Test
    void falseConditionsDoNotRestoreMovedOwnersInLaterBranches() {
        String prefix="define class Box as end fnc ready(Box v):bool{return false;} ";
        rejectOwnership(prefix+"fnc f():void{val Box owner=new Box(); if ready(owner as let v) {return;} else {ready(owner);fi}");
        rejectOwnership(prefix+"fnc f():void{val Box owner=new Box(); if ready(owner as let v) {return;} elif ready(owner) {return;fi}");
        rejectOwnership(prefix+"fnc f():void{val Box owner=new Box(); if ready(owner as let v) {return;fi ready(owner);}");
        rejectOwnership(prefix+"fnc f():void{val Box owner=new Box(); while ready(owner as let v) {return;} return;}");
    }

    @Test
    void buildOptimizationRetainsCaptureDependenciesAndShadowsModuleConstants() {
        var result = dev.oreslang.compiler.OresCompiler.compileForBuild("""
                pub const int value=0;
                fnc produce():int{stdio.stdout.write("p");return 42;}
                fnc ready(int n):bool{return n>0;}
                fnc consume(int n):void{stdio.stdout.write(n);return;}
                pub fnc main():void {
                  if ready(produce() as let value) {
                    if value>0 {consume(value);fi
                  fi
                  return;
                }
                """, dev.oreslang.compiler.BuildOptions.executable(java.util.Map.of()));
        assertTrue(result.retained(Parser.ROOT_MODULE+".produce"));
        assertTrue(result.retained(Parser.ROOT_MODULE+".consume"));
        assertDoesNotThrow(() -> TypeChecker.check(result.program()));
    }

    private static void rejectOwnership(String source) {
        var program = assertDoesNotThrow(() -> Parser.parse(source));
        var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("ownership"), error::getMessage);
    }

    private static void check(String source) {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
    }
    private static void reject(String source) { assertThrows(IllegalArgumentException.class, () -> check(source)); }
    private static String run(String source) {
        check(source);
        var output=new ByteArrayOutputStream();
        try(var context=Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID,source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

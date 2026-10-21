package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MatchExpressionTest {
    @TempDir Path temp;
    private static void check(String source) { TypeChecker.check(Parser.parse(source)); }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "match-expression.ores").build());
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test void expressionsBlocksAndNestedMatchesProduceValues() throws Exception {
        assertEquals("Ada\nunsupported\n9\n", run("""
                fnc name(bool supported): String {
                  val value = obj { name: "Ada" };
                  return match supported {
                    true -> match value {
                      structural { name: String } as named -> {
                        val result = named.name;
                        result
                      }
                      _ -> "unsupported"
                    }
                    false -> "unsupported"
                  };
                }
                pub fnc main(): void {
                  stdio.println(name(true));
                  stdio.println(name(false));
                  val result = match Some(9)
                    Some(n) -> n
                    None -> 0
                  end;
                  stdio.println(result);
                  return;
                }
                """));
    }

    @Test void incompatibleResultsAndMissingBlockValuesFail() {
        var mismatch = assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(bool flag): String {
                  return match flag { true -> "yes" false -> 42 };
                }
                """));
        assertTrue(mismatch.getMessage().contains("match arm 2"), mismatch.getMessage());
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(bool flag): void {
                  val result = match flag { true -> "yes" false -> 42 };
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(bool flag): void {
                  match flag { true -> "yes" false -> 42 }
                  return;
                }
                """));
        var missing = assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(bool flag): String {
                  return match flag { true -> { "yes"; } false -> "no" };
                }
                """));
        assertTrue(missing.getMessage().contains("final expression"));
    }

    @Test void closuresAreValuesAndHaveCheckedReturnTypes() throws Exception {
        assertEquals("Ada\n", run("""
                pub fnc main(): void {
                  val value = obj { name: "Ada" };
                  val getter = match value {
                    structural { name: String } as named -> || -> { return named.name; }
                    _ -> || -> { return "unsupported"; }
                  };
                  stdio.println(getter());
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(bool flag): void {
                  val getter = match flag {
                    true -> || -> { return "yes"; }
                    false -> || -> { return 42; }
                  };
                  return;
                }
                """));
        // Context propagates into unannotated lambda parameters in each arm.
        assertDoesNotThrow(() -> check("""
                fnc callback(bool flag): Fnc<int, String> {
                  return match flag {
                    true -> |n| -> { return "yes"; }
                    false -> |n| -> { return "no"; }
                  };
                }
                """));
    }

    @Test void blockReturnExitsTheEnclosingFunctionAndDefersRun() throws Exception {
        assertEquals("cleanup\nexit\n", run("""
                fnc name(bool flag): String {
                  val result = match flag {
                    true -> { defer stdio.println("cleanup"); return "exit"; }
                    false -> "continue"
                  };
                  stdio.println("after");
                  return result;
                }
                pub fnc main(): void {
                  stdio.println(name(true));
                  return;
                }
                """));
    }

    @Test void castsAndTypePatternsUseTheSameNominalRelationAndAliases() throws Exception {
        assertEquals("dog\n7\n", run("""
                define class Animal as
                end
                define class Dog extends Animal as
                  pub bark(): String { return "dog"; }
                end
                fnc name(Animal animal): String {
                  return match animal { Dog as dog -> dog.bark() _ -> "other" };
                }
                pub fnc main(): void {
                  val Animal animal = new Dog();
                  stdio.println(name(animal));
                  val Animal another = new Dog();
                  val number = match another as? Dog { Some(dog) -> 7 None -> 0 };
                  stdio.println(number);
                  return;
                }
                """));
        assertDoesNotThrow(() -> check("""
                fnc test(int value): int {
                  return match value { is int as n -> n };
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc test(int value): int {
                  val result = match value { int as n -> n };
                  return n;
                }
                """));
    }

    @Test void structuralTestsAndCastsRespectPublicDeclaredTypes() throws Exception {
        assertEquals("public\nprivate\nfalse\n", run("""
                define class PublicName as
                  pub val String name = "public";
                end
                define class PrivateName as
                  val String name = "secret";
                end
                pub fnc main(): void {
                  val publicName = new PublicName();
                  val privateName = new PrivateName();
                  stdio.println(match publicName {
                    structural { name: String } as named -> named.name
                    _ -> "missing"
                  });
                  stdio.println(match privateName {
                    is structural { name: String } -> "leaked"
                    _ -> "private"
                  });
                  stdio.println(privateName is structural { name: String });
                  return;
                }
                """));
    }

    @Test void exclusivityCoverageAndArrowRestrictionsApplyToExpressions() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(bool flag): int { return match flag { true -> 1 }; }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(int n): int { return match n { is int as x -> x is int as y -> y }; }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(bool flag): int { return match flag { true => 1 false -> 0 }; }
                """));
    }
    @Test void modulesRequireMemberSelection() throws Exception {
        assertEquals("module\n", run("""
                define module names as
                  pub val String name = "module";
                end
                pub fnc main(): void {
                  stdio.println(names.name);
                  return;
                }
                """));
        var module = assertThrows(IllegalArgumentException.class, () -> check("""
                define module names as
                  pub val String name = "module";
                end
                fnc bad(): void { val ns = names; return; }
                """));
        assertTrue(module.getMessage().contains("cannot be used as a value"));
        assertThrows(IllegalArgumentException.class, () -> check("""
                import module names from "./names.ores";
                fnc bad(): void { val ns = names; return; }
                """));
    }

    @Test void asyncMatchValuesAndEarlyReturnsPreserveCleanup() throws Exception {
        assertEquals("cleanup\nAda\n", run("""
                async fnc load(): String { return "Ada"; }
                async fnc name(bool flag): String {
                  val result = match flag {
                    true -> {
                      defer stdio.println("cleanup");
                      val pending = load();
                      val name = await pending;
                      return name;
                    }
                    false -> "unsupported"
                  };
                  stdio.println("after");
                  return result;
                }
                pub async fnc main(): void {
                  val pending = name(true);
                  stdio.println(await pending);
                  return;
                }
                """));
        assertEquals("Ada\n", run("""
                async fnc load(): String { return "Ada"; }
                pub async fnc main(): void {
                  val result = match true {
                    true -> { val pending = load(); await pending }
                    false -> "unsupported"
                  };
                  stdio.println(result);
                  return;
                }
                """));
    }

    @Test void expressionArmsCannotHideCapabilityEffectsOrDuplicateOwnership() {
        var program = Parser.parse("""
                pub fnc main(): void {
                  val result = match true { true -> { fs.write_text("blocked.txt", "effect"); 1 } false -> 0 };
                  return;
                }
                """);
        assertThrows(SecurityException.class, () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
        assertThrows(IllegalArgumentException.class, () -> check("""
                define class Box as
                end
                fnc bad(Box box): void {
                  val moved = match true { true -> box false -> box };
                  val duplicate = box;
                  return;
                }
                """));
    }

    @Test void matchInfersCommonNominalAndFunctionResultTypesAndAcceptsExplicitUnions() {
        assertDoesNotThrow(() -> check("""
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end
                fnc choose(bool flag): Animal {
                  val animal = match flag { true -> new Dog() false -> new Cat() };
                  return animal;
                }
                fnc factory(bool flag): Fnc<Animal> {
                  val create = match flag {
                    true -> || -> { return new Dog(); }
                    false -> || -> { return new Cat(); }
                  };
                  return create;
                }
                fnc heterogeneous(bool flag): int | String {
                  return match flag { true -> 42 false -> "name" };
                }
                """));
    }

    @Test void matchBorrowsCannotEscapeArmLocalsOrAllowOwnerMutation() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                define class Box as
                  pub let String name = "original";
                end
                fnc bad(bool flag): void {
                  let Box box = new Box();
                  val view = match flag { true -> &box false -> &box };
                  box.name = "changed";
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                define class Box as
                end
                fnc bad(bool flag): &Box {
                  val Box box = new Box();
                  return match flag { true -> &box false -> &box };
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> check("""
                define class Box as
                end
                fnc bad(bool flag): void {
                  val view = match flag {
                    true -> { val box = new Box(); &box }
                    false -> { val box = new Box(); &box }
                  };
                  return;
                }
                """));
        assertDoesNotThrow(() -> check("""
                define class Box as
                end
                fnc good(bool flag, &Box box): &Box {
                  return match flag { true -> box false -> box };
                }
                """));
    }

    @Test void structuralBindingsCannotUseMutableFieldContentsAsTypeProof() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(): String {
                  let { name: int | String } value = obj { name: "original" };
                  return match value {
                    structural { name: String } as named -> named.name
                    _ -> "missing"
                  };
                }
                """));
    }

    @Test void typedAliasesCannotTurnBorrowedSubjectsIntoOwnedResults() {
        var error = assertThrows(IllegalArgumentException.class, () -> check("""
                define class Box as
                end
                fnc bad(&Box source): Box {
                  return match source { Box as owned -> owned _ -> new Box() };
                }
                """));
        assertTrue(error.getMessage().contains("cannot erase"), error.getMessage());
    }

    @Test void checkedOptionalAndPatternStructuralViewsAgree() throws Exception {
        assertEquals("Ada\nAda\nAda\n", run("""
                pub fnc main(): void {
                  val first = obj { name: "Ada" };
                  val cast = first as structural { name: String };
                  stdio.println(cast.name);
                  val second = obj { name: "Ada" };
                  val maybe = second as? structural { name: String };
                  stdio.println(match maybe { Some(named) -> named.name None -> "missing" });
                  val third = obj { name: "Ada" };
                  if third is structural { name: String } as named then
                    stdio.println(named.name);
                  fi
                  return;
                }
                """));
    }

    @Test void buildRetainsFunctionsAndTypesReferencedOnlyFromExpressionArms() {
        var result = OresCompiler.compileForBuild("""
                define class User as
                  pub val String name = "Ada";
                end
                define module selected as
                  pub fnc name(): String { stdio.println("selected"); return "selected"; }
                end
                define module unused as
                  pub fnc name(): String { return "unused"; }
                end
                pub fnc main(): void {
                  val user = new User();
                  val getter = match user {
                    User as named -> || -> { return selected.name() + named.name; }
                    _ -> || -> { return "fallback"; }
                  };
                  stdio.println(getter());
                  return;
                }
                """, BuildOptions.executable());
        assertTrue(result.retained("selected.name"));
        assertTrue(result.removed("unused.name"));
        assertDoesNotThrow(() -> TypeChecker.check(result.program()));
    }

    @Test void cyclicAliasInATypePatternFailsWithADiagnostic() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(int value): int {
                  return match value { Loop as x -> x _ -> 0 };
                }
                type Loop = Loop;
                """));
    }

    @Test void importedModulePathsMustTerminateInAnExportedValueAtRuntime() throws Exception {
        Path child = temp.resolve("names.ores");
        Path entry = temp.resolve("main.ores");
        Files.writeString(child, """
                define module Names as
                  pub fnc name(): String { return "Ada"; }
                end
                """);
        Files.writeString(entry, """
                import * as deps from './names';
                pub async fnc main(): void {
                  stdio.println(deps.Names.name());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
        assertEquals("Ada\n", out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        Files.writeString(entry, """
                import * as deps from './names';
                pub fnc main(): void {
                  val ns = deps.Names;
                  return;
                }
                """);
        var error = assertThrows(RuntimeException.class, () -> LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream()));
        assertTrue(error.getMessage().contains("module namespace"), error.getMessage());
    }

    @Test void structuralMethodsMixWithFieldsAndUseInheritedOverloads() throws Exception {
        assertEquals("Hi Ada\nhello\nHi Ada\n", run("""
                define interface Named as
                  name: String;
                end
                define class Parent implements Named as
                  pub val String name = "Ada";
                  pub greet(String prefix): String { return prefix + self.name; }
                end
                define class Child extends Parent as
                  pub greet(): String { return "hello"; }
                end
                fnc greet(Named value): String {
                  return match value {
                    structural { name: String; fnc greet(String prefix) => String; } as named -> named.greet("Hi ")
                    _ -> "unsupported"
                  };
                }
                pub fnc main(): void {
                  val child = new Child();
                  stdio.println(greet(new Child()));
                  val getter = match child {
                    structural { fnc greet() => String; fnc greet(String prefix) => String; } as named ->
                      || -> { return named.greet(); }
                    _ -> || -> { return "unsupported"; }
                  };
                  stdio.println(getter());
                  val other = new Child();
                  val cast = other as structural { fnc greet(String prefix) => String; };
                  stdio.println(cast.greet("Hi "));
                  return;
                }
                """));
    }

    @Test void structuralMethodChecksCompareSignaturesWithoutExecutingMethods() throws Exception {
        assertEquals("true\nfalse\nfalse\nfalse\nfalse\nfalse\n", run("""
                define class Candidate as
                  pub greet(String prefix): String { stdio.println("must not execute"); return prefix; }
                  hidden(): String { return "hidden"; }
                  pub static fnc staticOnly(): String { return "static"; }
                end
                pub fnc main(): void {
                  val value = new Candidate();
                  stdio.println(value is structural { fnc greet(String prefix) => String; });
                  stdio.println(value is structural { fnc greet(int prefix) => String; });
                  stdio.println(value is structural { fnc greet(String prefix) => int; });
                  stdio.println(value is structural { fnc greet() => String; });
                  stdio.println(value is structural { fnc hidden() => String; });
                  stdio.println(value is structural { fnc staticOnly() => String; });
                  return;
                }
                """));
    }

    @Test void structuralMethodSignaturesRespectVarianceAndMutability() throws Exception {
        assertEquals("true\nfalse\ntrue\nfalse\n", run("""
                define class Base as
                end
                define class Child extends Base as
                end
                define class Candidate as
                  pub choose(Base input): Child { return new Child(); }
                  pub update(Base mut input): void { return; }
                end
                pub fnc main(): void {
                  val value = new Candidate();
                  stdio.println(value is structural { fnc choose(Child input) => Base; });
                  stdio.println(value is structural { fnc choose(Base input) => String; });
                  stdio.println(value is structural { fnc update(Base mut input) => void; });
                  stdio.println(value is structural { fnc update(Base input) => void; });
                  return;
                }
                """));
    }

    @Test void structuralMethodSignaturesPreserveAsyncModeAndAwaitFutureResults() throws Exception {
        assertEquals("true\nfalse\n8\n", run("""
                async fnc delayed(): int { return 8; }
                define class Candidate as
                  val Future<int> pending = delayed();
                  pub load(): Future<int> { return self.pending; }
                end
                pub async fnc main(): void {
                  val value = new Candidate();
                  stdio.println(value is structural { fnc load() => Future<int>; });
                  stdio.println(value is structural { async fnc load() => int; });
                  val result = match value {
                    structural { fnc load() => Future<int>; } as loader -> await loader.load()
                    _ -> 0
                  };
                  stdio.println(result);
                  return;
                }
                """));
    }

    @Test void structuralMethodCallsPreserveBorrowedParameterOwnership() throws Exception {
        assertEquals("5\n5\n", run("""
                define class Box as
                  pub val int value = 5;
                end
                define class Reader as
                  pub read(&Box box): int { return box.value; }
                end
                pub fnc main(): void {
                  val box = new Box();
                  val reader = new Reader();
                  stdio.println(match reader {
                    structural { fnc read(&Box box) => int; } as view -> view.read(&box)
                    _ -> 0
                  });
                  stdio.println(box.value);
                  return;
                }
                """));
    }

    @Test void structuralMethodSlotsAreDistinctFromCallableFieldsAndNonReifiableMethods() throws Exception {
        assertEquals("false\n", run("""
                pub fnc main(): void {
                  val value = obj { print: || -> { return "field"; } };
                  stdio.println(value is structural { fnc print() => String; });
                  return;
                }
                """));
        var error = assertThrows(IllegalArgumentException.class, () -> check("""
                define class Candidate as
                  pub print(): String { return "hello"; }
                end
                fnc bad(Candidate value): void {
                  val method = match value {
                    structural { fnc print() => String; } as view -> view.print
                    _ -> || -> { return "fallback"; }
                  };
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("direct-call-only"), error.getMessage());
    }

    @Test void malformedStructuralMethodContractsFailWithDiagnostics() {
        for (String shape : new String[] {
                "fnc print() -> String;", "fnc print<T>(T value) => T;",
                "fnc print() => String; fnc print() => int;",
                "print: String; fnc print() => String;",
                "fnc print() => String; print: String;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    "fnc bad(int value): bool { return value is structural { " + shape + " }; }"), shape);
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    "fnc bad(int value): int { return match value { structural { " + shape
                            + " } as view -> 1 _ -> 0 }; }"), shape);
        }
    }

    @Test void privateOverridesDoNotExposeInheritedPublicMethodsThroughStructure() throws Exception {
        assertEquals("false\n", run("""
                define class Parent as
                  pub print(): String { return "parent"; }
                end
                define class Child extends Parent as
                  print(): String { return "child"; }
                end
                pub fnc main(): void {
                  val value = new Child();
                  stdio.println(value is structural { fnc print() => String; });
                  return;
                }
                """));
    }

    @Test void quotedCallableFieldsCannotSpoofMethodSlotsOrExhaustiveness() {
        var error = assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(): int {
                  val value = obj { "instance$print$arity0$generics0": || -> { return 1; } };
                  return match value { is structural { fnc print() => int; } -> 1 };
                }
                """));
        assertTrue(error.getMessage().contains("non-exhaustive"), error.getMessage());
    }

}

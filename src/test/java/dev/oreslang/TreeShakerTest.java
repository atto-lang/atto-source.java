package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.ast.Ast;
import dev.oreslang.types.AggregateConstructionPlan;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TreeShakerTest {
    @Test
    void aggregateSpreadSurvivesRewriteAndConstantCallSpecialization() {
        // The ordinary rewrite and the constant-call inlining/substitution
        // path both reconstruct aggregate AST nodes. Neither may turn a
        // spread item (name == null) into a named initializer.
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                fnc extend(int n): record{foo: int, bar: int} {
                    return record{...record{foo: 5}, bar: n};
                }

                pub fnc main(): void {
                    const base = record{foo: 1};
                    const copy = record{...base, bar: 2};
                    const inlined = extend(9);
                    stdio.println(copy.foo);
                    stdio.println(inlined.bar);
                    return;
                }
                """, BuildOptions.executable(Map.of()));

        Ast.FunctionDecl main = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("main"))
                .findFirst().orElseThrow();

        Ast.BindingStmt copy = main.body().stream()
                .filter(Ast.BindingStmt.class::isInstance)
                .map(Ast.BindingStmt.class::cast)
                .filter(binding -> binding.name().equals("copy"))
                .findFirst().orElseThrow();
        Ast.ObjectExpr copied = assertInstanceOf(Ast.ObjectExpr.class, copy.initializer());
        assertEquals(2, copied.fields().size());
        assertTrue(copied.fields().getFirst().spread());
        assertNull(copied.fields().getFirst().name());
        assertEquals("bar", copied.fields().get(1).name());

        Ast.BindingStmt inlined = main.body().stream()
                .filter(Ast.BindingStmt.class::isInstance)
                .map(Ast.BindingStmt.class::cast)
                .filter(binding -> binding.name().equals("inlined"))
                .findFirst().orElseThrow();
        Ast.ObjectExpr specialized = assertInstanceOf(Ast.ObjectExpr.class, inlined.initializer(),
                "constant-call inlining should retain aggregate literal identity");
        assertTrue(specialized.fields().getFirst().spread());
        assertNull(specialized.fields().getFirst().name());
        assertEquals("bar", specialized.fields().get(1).name());

        // Compiler entry point must revalidate both new optimizer-created ASTs
        // rather than inherit the input program's identity-bound authority.
        assertFalse(result.needsSpreadRecheck(),
                "compileForBuild must not return a pending spread validation");
        assertDoesNotThrow(() -> AggregateConstructionPlan.Checked.requireAdmitted(result.program(), copied));
        assertDoesNotThrow(() -> AggregateConstructionPlan.Checked.requireAdmitted(result.program(), specialized));
    }

    @Test
    void rawOptimizerCannotTransferSpreadAdmissionByASTEquality() {
        String source = """
                pub fnc main(): void {
                    const base = record{x: 1};
                    const copy = record{...base, x: 2};
                    stdio.println(copy.x);
                    return;
                }
                """;
        TreeShaker.Result raw = TreeShaker.shake(
                OresCompiler.parseAndTypeCheck(source),
                BuildOptions.executable(Map.of()));
        assertTrue(raw.needsSpreadRecheck(),
                "rewritten aggregate AST nodes must invalidate original checked identity");
        Ast.ObjectExpr rawCopy = raw.program().modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(f -> f.name().equals("main"))
                .flatMap(f -> f.body().stream())
                .filter(Ast.BindingStmt.class::isInstance)
                .map(Ast.BindingStmt.class::cast)
                .filter(b -> b.name().equals("copy"))
                .map(Ast.BindingStmt::initializer)
                .filter(Ast.ObjectExpr.class::isInstance)
                .map(Ast.ObjectExpr.class::cast)
                .findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,
                () -> AggregateConstructionPlan.Checked.requireAdmitted(raw.program(), rawCopy),
                "raw tree shaking must never inherit type or ownership admission");

        TreeShaker.Result admitted = OresCompiler.compileForBuild(
                source, BuildOptions.executable(Map.of()));
        assertFalse(admitted.needsSpreadRecheck());
    }

    @Test
    void cliDefinesOverrideEnvironmentDefinesDeterministically() {
        Map<String, String> merged = BuildOptions.mergeDefines(
                Map.of(
                        "ORESLANG_BUILD_DEFINES", "use_a=false,backend=jvm",
                        "ORESLANG_DEFINE_DEBUG", "false"),
                List.of("use_a=true", "backend=native"));

        assertEquals("true", merged.get("use_a"));
        assertEquals("native", merged.get("backend"));
        assertEquals("false", merged.get("DEBUG"));
    }

    @Test
    void buildDefineFoldsFunctionReferenceAndRemovesDeadModule() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool use_a = false;

                define module A
                  pub fnc foo(): String {
                    return "hi";
                  }
                end

                define module B
                  pub fnc foo(): String {
                    return "bye";
                  }
                end

                type F = typeof fnc() => String;

                pub fnc choose(bool t): F {
                  return t ? A.foo : B.foo;
                }

                pub routine main(): void {
                  val F selected = choose(use_a);
                  stdio.stdout.write(selected());
                  return;
                }
                """, BuildOptions.executable(Map.of("use_a", "true")));

        assertTrue(result.retained("A.foo"));
        assertTrue(result.removed("B.foo"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".use_a"),
                "a const used only to fold build-time control flow should disappear");
        assertTrue(result.removed(Parser.ROOT_MODULE + ".choose"),
                "a constant-argument helper should disappear after specialization when no runtime call remains");

        Set<String> modules = result.program().modules().stream()
                .map(module -> module.name())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(modules.contains("A"));
        assertFalse(modules.contains("B"));
        assertTrue(modules.contains(Parser.ROOT_MODULE));
    }

    @Test
    void buildDefineFoldsIfStatementBeforeReachability() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool debug_backend = false;

                define module DebugBackend
                  pub routine run(): void {
                    stdio.stdout.write("debug");
                    return;
                  }
                end

                define module ReleaseBackend
                  pub routine run(): void {
                    stdio.stdout.write("release");
                    return;
                  }
                end

                pub routine main(): void {
                  if debug_backend; do
                    DebugBackend.run();
                  else
                    ReleaseBackend.run();
                  fi
                  return;
                }
                """, BuildOptions.executable(Map.of("debug_backend", "true")));

        assertTrue(result.retained("DebugBackend.run"));
        assertTrue(result.removed("ReleaseBackend.run"));
    }

    @Test
    void reifiedModuleAliasRetainsItsRuntimeVisibleCallableSurface() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define module service
                  pub fnc transform(int value): int {
                    return value + 1;
                  }

                  pub routine direct_only(int value): int {
                    return value + 2;
                  }

                  fnc private_helper(): int {
                    return 99;
                  }
                end

                pub routine main(): void {
                  val alias = service;
                  val Fnc<int, int> callback = alias.transform;
                  stdio.stdout.write(callback(4));
                  stdio.stdout.write(alias.direct_only(4));
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        assertTrue(result.retained("service.transform"));
        assertTrue(result.retained("service.direct_only"));
        assertTrue(result.removed("service.private_helper"));
    }

    @Test
    void foldedConditionalPreservesItsLexicalScope() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool enabled = true;

                pub routine main(): void {
                  if enabled {
                    val hidden = 1;
                    stdio.stdout.write(hidden);
                  } fi
                  return;
                }
                """, BuildOptions.executable(Map.of("enabled", "true")));

        Ast.FunctionDecl main = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("main"))
                .findFirst()
                .orElseThrow();

        assertInstanceOf(Ast.BlockStmt.class, main.body().getFirst(),
                "constant folding must not flatten an if arm into its parent lexical scope");
    }

    @Test
    void directOnlyRoutineCallsAreEligibleForConstantInlining() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                routine plus_one(int value): int {
                  return value + 1;
                }

                pub routine main(): void {
                  stdio.stdout.write(plus_one(41));
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        assertTrue(
                result.removed(Parser.ROOT_MODULE + ".plus_one"),
                "direct-only routines should be at least as inlineable as reifiable fnc declarations");
    }

    @Test
    void libraryBuildPreservesPublicApiButStillDropsPrivateDeadSymbols() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub fnc public_answer(): int { return 42; }
                fnc hidden_answer(): int { return 7; }
                """, BuildOptions.library(Map.of()));

        assertTrue(result.retained(Parser.ROOT_MODULE + ".public_answer"));
        assertTrue(result.rootSymbols().contains(Parser.ROOT_MODULE + ".public_answer"));
        assertFalse(result.rootSymbols().contains(Parser.ROOT_MODULE + ".hidden_answer"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".hidden_answer"));
    }

    @Test
    void defineMustTargetAConstDeclaration() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileForBuild("""
                pub val bool use_a = false;
                pub routine main(): void { return; }
                """, BuildOptions.executable(Map.of("use_a", "true"))));
    }

    @Test
    void treeShakingDoesNotHideTypeErrorsInDeadBranches() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileForBuild("""
                pub const bool use_a = true;

                define module A
                  pub fnc foo(): int { return 1; }
                end

                define module B
                  pub fnc foo(): int { return "not an int"; }
                end

                pub routine main(): void {
                  val chosen = use_a ? A.foo : B.foo;
                  chosen();
                  return;
                }
                """, BuildOptions.executable(Map.of("use_a", "true"))));
    }

    @Test
    void treeShakingPreservesExplicitReferentMutability() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define class Child as
                  pub let int value = 0;
                end

                define class Holder as
                  pub const mut Child stable = new Child();
                  pub let mut Child replaceable = new Child();
                end

                pub routine main(): void {
                  val Holder holder = new Holder();
                  holder.stable.value = 1;
                  holder.replaceable.value = 2;
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        Ast.ClassDecl holder = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(klass -> klass.name().equals("Holder"))
                .findFirst()
                .orElseThrow();

        Ast.FieldDecl stable = holder.fields().stream()
                .filter(field -> field.name().equals("stable"))
                .findFirst().orElseThrow();
        Ast.FieldDecl replaceable = holder.fields().stream()
                .filter(field -> field.name().equals("replaceable"))
                .findFirst().orElseThrow();

        assertEquals(Ast.BindingKind.CONST, stable.bindingKind());
        assertTrue(stable.mutableReferent(), "const mut must survive tree rewriting");
        assertEquals(Ast.BindingKind.LET, replaceable.bindingKind());
        assertTrue(replaceable.mutableReferent(), "let mut must survive tree rewriting");
    }


    @Test
    void treeShakingPreservesLocalSelectAndForOfMutability() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub routine main(): void {
                  const mut local = struct{foo: string}{foo: "a"};
                  local.foo = "b";

                  for let mut item of arr[1] do
                    item = 2;
                  done

                  val Channel<int> input = Channel.new<int>(1);
                  select {
                    case val message = readch(input) -> { }
                    default: { }
                  }
                  return;
                }
                """, BuildOptions.executable(Map.of()));

        Ast.FunctionDecl main = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("main"))
                .findFirst().orElseThrow();

        Ast.BindingStmt local = main.body().stream()
                .filter(Ast.BindingStmt.class::isInstance)
                .map(Ast.BindingStmt.class::cast)
                .filter(binding -> binding.name().equals("local"))
                .findFirst().orElseThrow();
        assertEquals(Ast.BindingKind.CONST, local.kind());
        assertTrue(local.mutableReferent());

        Ast.ForOfStmt loop = main.body().stream()
                .filter(Ast.ForOfStmt.class::isInstance)
                .map(Ast.ForOfStmt.class::cast)
                .findFirst().orElseThrow();
        assertEquals(Ast.BindingKind.LET, loop.bindingKind());
        assertTrue(loop.mutableReferent());

        Ast.SelectStmt selected = main.body().stream()
                .filter(Ast.SelectStmt.class::isInstance)
                .map(Ast.SelectStmt.class::cast)
                .findFirst().orElseThrow();
        Ast.SelectArm read = selected.arms().stream()
                .filter(arm -> arm.operation() == Ast.ChannelOperation.READ)
                .findFirst().orElseThrow();
        assertEquals(Ast.BindingKind.VAL, read.bindingKind());
        assertTrue(read.mutableReferent());
    }


    @Test
    void treeShakingPreservesCheckedClassViewForRewrittenIndexReceiver() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define class Parent as
                    pub let int id = 7;
                end

                pub fnc read(): int {
                    val Parent value = new Parent();
                    return (rt borrow value)["id"];
                }
                """, BuildOptions.library(Map.of()));

        Ast.FunctionDecl read = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("read"))
                .findFirst()
                .orElseThrow();

        Ast.ReturnStmt returned = read.body().stream()
                .filter(Ast.ReturnStmt.class::isInstance)
                .map(Ast.ReturnStmt.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.IndexExpr indexed = assertInstanceOf(Ast.IndexExpr.class, returned.value());

        assertEquals(
                "Parent",
                Ast.CheckedClassView.lookup(indexed.receiver()),
                "optimizer must transfer the type checker's static class-domain proof");
    }

    @Test
    void treeShakingPreservesCheckedClassViewThroughConstantCallInlining() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define class Parent as
                    pub let int id = 7;
                end

                fnc inlineRead(): int {
                    return (new Parent())["id"];
                }

                pub fnc read(): int {
                    return inlineRead();
                }
                """, BuildOptions.library(Map.of()));

        Ast.FunctionDecl read = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("read"))
                .findFirst()
                .orElseThrow();

        Ast.ReturnStmt returned = read.body().stream()
                .filter(Ast.ReturnStmt.class::isInstance)
                .map(Ast.ReturnStmt.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.IndexExpr indexed = assertInstanceOf(Ast.IndexExpr.class, returned.value());

        assertEquals(
                "Parent",
                Ast.CheckedClassView.lookup(indexed.receiver()),
                "constant-call substitution must preserve the type checker's static class-domain proof");
    }

    @Test
    void treeShakingPreservesCheckedOptionDepthForRewrittenCall() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub fnc read(Option<Option<int>> value): int {
                    return value.unwrap_all();
                }
                """, BuildOptions.library(Map.of()));

        Ast.FunctionDecl read = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("read"))
                .findFirst()
                .orElseThrow();

        Ast.ReturnStmt returned = read.body().stream()
                .filter(Ast.ReturnStmt.class::isInstance)
                .map(Ast.ReturnStmt.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, returned.value());

        assertEquals(
                2,
                Ast.CheckedOptionDepth.require(call),
                "optimizer must transfer the type checker's checked Option depth");
    }

    @Test
    void treeShakingPreservesCheckedOptionDepthThroughConstantCallInlining() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                fnc inlineRead(): int {
                    return Some(Some(42)).unwrap_all();
                }

                pub fnc read(): int {
                    return inlineRead();
                }
                """, BuildOptions.library(Map.of()));

        Ast.FunctionDecl read = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("read"))
                .findFirst()
                .orElseThrow();

        Ast.ReturnStmt returned = read.body().stream()
                .filter(Ast.ReturnStmt.class::isInstance)
                .map(Ast.ReturnStmt.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, returned.value());

        assertEquals(
                2,
                Ast.CheckedOptionDepth.require(call),
                "constant-call substitution must preserve the checked Option depth");
    }

    @Test
    void treeShakingPreservesStructuralFieldDomainForRewrittenReceiver() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define class Product as
                    pub val float x = 3.0;
                    pub val float y = 4.0;
                    private val string hidden = "secret";
                end

                pub fnc read(structural Product value, bool chooseLeft, string key): float {
                    return (chooseLeft ? value : value)[key];
                }
                """, BuildOptions.library(Map.of()));

        Ast.FunctionDecl read = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("read"))
                .findFirst()
                .orElseThrow();

        Ast.ReturnStmt returned = read.body().stream()
                .filter(Ast.ReturnStmt.class::isInstance)
                .map(Ast.ReturnStmt.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.IndexExpr indexed = assertInstanceOf(Ast.IndexExpr.class, returned.value());
        assertInstanceOf(Ast.ConditionalExpr.class, indexed.receiver());

        assertEquals(
                Set.of("x", "y"),
                Ast.CheckedStructuralFieldDomain.lookup(indexed.receiver()),
                "optimizer must transfer the checked structural field domain");
    }
}

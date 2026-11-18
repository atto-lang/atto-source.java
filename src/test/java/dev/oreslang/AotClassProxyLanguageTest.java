package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-backed source tests; hosted execution is not Native Image evidence. */
final class AotClassProxyLanguageTest {

    private static final String SIMPLE = """
            define class Foo as
              pub add(int a, int b): int {
                return a + b;
              }
              pub greet(int a): int {
                return a + 10;
              }
              pub greet(int a, int b): int {
                return a + b + 10;
              }
              private hidden(): int {
                return 99;
              }
              pub static fnc version(): int {
                return 1;
              }
            end
            pub routine main(): void {
              val proxy = new Proxy(new Foo());
              stdio.stdout.write(proxy.add(20, 22));
              stdio.stdout.write(proxy.greet(2));
              stdio.stdout.write(proxy.greet(4, 5));
              return;
            }
            """;

    @Test
    void exactAotProxySyntaxExpandsBeforeTypeAndOwnershipChecking() {
        Ast.Program parsed = Parser.parse(SIMPLE);
        Ast.ClassDecl generated = parsed.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(declaration -> declaration instanceof Ast.ClassDecl klass
                        && klass.name().equals("FooProxy"))
                .map(declaration -> (Ast.ClassDecl) declaration).findFirst().orElseThrow();

        assertEquals(1, generated.fields().size());
        assertEquals(1, generated.constructor().arity());
        assertEquals("Foo", generated.constructor().parameters().getFirst().type().name());
        assertEquals(3, generated.methods().size(), "public instance methods mirrored by name/arity");
        assertTrue(generated.methods().stream().noneMatch(Ast.MethodDecl::isStatic));
        assertTrue(generated.methods().stream().anyMatch(method ->
                method.name().equals("greet") && method.arity() == 1));
        assertTrue(generated.methods().stream().anyMatch(method ->
                method.name().equals("greet") && method.arity() == 2));

        Ast.FunctionDecl main = parsed.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(declaration -> declaration instanceof Ast.FunctionDecl fn
                        && fn.name().equals("main"))
                .map(declaration -> (Ast.FunctionDecl) declaration).findFirst().orElseThrow();
        Ast.BindingStmt binding = (Ast.BindingStmt) main.body().getFirst();
        assertInstanceOf(Ast.NewExpr.class, binding.initializer());
        assertEquals("FooProxy", ((Ast.NewExpr) binding.initializer()).type().name());

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(SIMPLE),
                "generated methods must pass real type and ownership admission");
    }

    @Test
    void generatedForwardersExecuteAsOrdinaryClassMethods() throws Exception {
        assertEquals("421219", run(SIMPLE));
    }

    @Test
    void targetIsConstructedExactlyOnce() throws Exception {
        String source = """
                let int built = 0;
                define class Foo as
                  constructor() {
                    built = built + 1;
                  }
                  pub read(): int { return built; }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo());
                  stdio.stdout.write(p.read());
                  return;
                }
                """;
        // The constructor argument is retained, never duplicated by synthesis.
        Ast.Program parsed = Parser.parse(source);
        Ast.FunctionDecl main = parsed.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl f && f.name().equals("main"))
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
        Ast.NewExpr proxy = (Ast.NewExpr) ((Ast.BindingStmt) main.body().getFirst()).initializer();
        assertEquals(1, proxy.arguments().size());
        assertEquals("Foo", ((Ast.NewExpr) proxy.arguments().getFirst()).type().name());
        // Checking only the AST shape is insufficient: run the real guest
        // constructor and verify the side effect occurs exactly once.
        assertEquals("1", run(source));
    }

    @Test
    void rejectsArbitraryRuntimeTargetInference() {
        String source = """
                define class Foo as end
                pub routine main(): void {
                  val original = new Foo();
                  val p = new Proxy(original);
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("AOT_PROXY_UNSUPPORTED"));
    }

    @Test
    void rejectsUnboundTargetAndGeneratedNameCollision() {
        String unknown = """
                pub routine main(): void {
                  val p = new Proxy(new Missing());
                  return;
                }
                """;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(unknown)).getMessage().contains("AOT_PROXY_UNSUPPORTED"));

        String collision = """
                define class Foo as end
                define class FooProxy as end
                pub routine main(): void {
                  val p = new Proxy(new Foo());
                  return;
                }
                """;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(collision)).getMessage().contains("collides"));
    }

    @Test
    void rejectsUnsupportedGenericAndEffectfulMethodSurfaces() {
        String generic = """
                define class Foo<T> as end
                pub routine main(): void {
                  val p = new Proxy(new Foo());
                  return;
                }
                """;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(generic)).getMessage().contains("generic"));

        String suspending = """
                define class Foo as
                  pub suspends get(): int { return 1; }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo());
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(suspending));
        assertTrue(error.getMessage().contains("AOT_PROXY_UNSUPPORTED"));
    }

    @Test
    void rejectsCrossModuleGeneratedNameCapture() {
        Ast.ClassDecl foo = new Ast.ClassDecl("Foo", false, List.of(), List.of(), List.of());
        Ast.ClassDecl existing = new Ast.ClassDecl("FooProxy", false, List.of(), List.of(), List.of());
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("first", List.of(foo)),
                new Ast.ModuleDecl("second", List.of(existing))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.ast.StaticProxySynthesis.expand(program, Set.of("Foo")));
        assertTrue(error.getMessage().contains("collides"), error.getMessage());
    }

    @Test
    void rejectsImportedNamesAndUnresolvedWildcardImports() {
        Ast.Program baseline = proxySurfaceProgram(List.of());
        Ast.ImportDecl importedName = new Ast.ImportDecl(Ast.ImportKind.CLASS,
                List.of("FooProxy"), false, null, "./other");
        Ast.ImportDecl wildcard = new Ast.ImportDecl(Ast.ImportKind.ALL,
                List.of(), true, "external", "./other");

        for (Ast.ImportDecl imported : List.of(importedName, wildcard)) {
            Ast.Program program = new Ast.Program(List.of(imported), baseline.modules());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(program, Set.of("Foo")));
            assertTrue(error.getMessage().contains("AOT_PROXY_UNSUPPORTED"), error.getMessage());
        }
    }

    @Test
    void rejectsImportedTargetNameCaptureBeforeProxyGeneration() {
        Ast.Program baseline = proxySurfaceProgram(List.of());
        Ast.ImportDecl importedTarget = new Ast.ImportDecl(Ast.ImportKind.CLASS,
                List.of("Foo"), false, null, "./other");
        Ast.Program program = new Ast.Program(List.of(importedTarget), baseline.modules());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.ast.StaticProxySynthesis.expand(program, Set.of("Foo")));
        assertTrue(error.getMessage().contains("collides with an imported name"),
                error.getMessage());
    }

    @Test
    void rejectsAnnotatedTargetConstructorRatherThanSilentlyDroppingPolicy() {
        Ast.ConstructorDecl constructor = new Ast.ConstructorDecl(
                Ast.Visibility.PUBLIC, List.of(),
                List.of(new Ast.Annotation("RequiredPolicy", List.of())), List.of());
        Ast.ClassDecl klass = new Ast.ClassDecl("Foo", Ast.Visibility.PUBLIC, false,
                Ast.ActorKind.NONE, List.of(), List.of(), List.of(), List.of(),
                constructor, List.of());
        Ast.Program program = new Ast.Program(
                List.of(new Ast.ModuleDecl("first", List.of(klass))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.ast.StaticProxySynthesis.expand(program, Set.of("Foo")));
        assertTrue(error.getMessage().contains("annotated target constructor"),
                error.getMessage());
    }

    @Test
    void rejectsMutableStructSignaturesEvenWhenNested() {
        Ast.TypeRef mutable = Ast.TypeRef.structType(
                java.util.Map.of("count", Ast.TypeRef.simple("int")));
        Ast.TypeRef nested = new Ast.TypeRef("Option", List.of(mutable), false);
        for (Ast.TypeRef type : List.of(mutable, nested)) {
            Ast.MethodDecl argument = proxySurfaceMethod(Ast.TypeRef.simple("void"),
                    List.of(new Ast.Param(type, "value")), List.of());
            Ast.MethodDecl result = proxySurfaceMethod(type, List.of(), List.of());
            for (Ast.MethodDecl method : List.of(argument, result)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                                proxySurfaceProgram(List.of(method)), Set.of("Foo")));
                assertTrue(error.getMessage().contains("ownership/borrow")
                        || error.getMessage().contains("reborrowing"), error.getMessage());
            }
        }
    }

    @Test
    void rejectsAmbiguousGeneratedMethodSlot() {
        Ast.MethodDecl first = proxySurfaceMethod(Ast.TypeRef.simple("int"),
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "a")), List.of());
        Ast.MethodDecl second = proxySurfaceMethod(Ast.TypeRef.simple("int"),
                List.of(new Ast.Param(Ast.TypeRef.simple("String"), "a")), List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                        proxySurfaceProgram(List.of(first, second)), Set.of("Foo")));
        assertTrue(error.getMessage().contains("ambiguous proxy method slot"), error.getMessage());
    }

    @Test
    void rejectsMethodAnnotationRatherThanSilentlyDroppingContract() {
        Ast.MethodDecl annotated = proxySurfaceMethod(Ast.TypeRef.simple("int"),
                List.of(), List.of(new Ast.Annotation("RequiredPolicy", List.of())));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                        proxySurfaceProgram(List.of(annotated)), Set.of("Foo")));
        assertTrue(error.getMessage().contains("annotated method"), error.getMessage());
    }

    @Test
    void rejectsBorrowedMethodTypesIncludingNestedGenericArguments() {
        Ast.TypeRef borrowed = Ast.TypeRef.borrowed(Ast.TypeRef.simple("Foo"), false);
        Ast.TypeRef nested = new Ast.TypeRef("Option", List.of(borrowed), false);
        Ast.MethodDecl borrowedArg = proxySurfaceMethod(Ast.TypeRef.simple("void"),
                List.of(new Ast.Param(nested, "value")), List.of());
        Ast.MethodDecl borrowedResult = proxySurfaceMethod(nested, List.of(), List.of());
        for (Ast.MethodDecl method : List.of(borrowedArg, borrowedResult)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            proxySurfaceProgram(List.of(method)), Set.of("Foo")));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
    }

    @Test
    void rejectsTargetEscapeHiddenBehindTypeAliases() {
        Ast.TypeAliasDecl alias = new Ast.TypeAliasDecl("AliasFoo", List.of(),
                Ast.TypeRef.simple("Foo"));
        Ast.TypeRef nested = new Ast.TypeRef("Option",
                List.of(Ast.TypeRef.simple("AliasFoo")), false);
        for (Ast.TypeRef result : List.of(Ast.TypeRef.simple("AliasFoo"), nested)) {
            Ast.MethodDecl method = proxySurfaceMethod(result, List.of(), List.of());
            Ast.ClassDecl klass = new Ast.ClassDecl("Foo", false, List.of(),
                    List.of(), List.of(method));
            Ast.Program program = new Ast.Program(List.of(
                    new Ast.ModuleDecl("first", List.of(alias, klass))));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            program, Set.of("Foo")));
            assertTrue(error.getMessage().contains("ownership/borrow"),
                    error.getMessage());
        }
    }

    @Test
    void rejectsTargetIdentityEscapeViaDirectAndNestedReturnTypes() {
        Ast.TypeRef target = Ast.TypeRef.simple("Foo");
        Ast.TypeRef nested = new Ast.TypeRef("Option", List.of(target), false);
        for (Ast.TypeRef result : List.of(target, nested, Ast.TypeRef.simple("self"))) {
            Ast.MethodDecl method = proxySurfaceMethod(result, List.of(), List.of());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            proxySurfaceProgram(List.of(method)), Set.of("Foo")));
            assertTrue(error.getMessage().contains("expose the wrapped target"),
                    error.getMessage());
        }
        // Returning a primitive remains a legal, ordinary typed forwarder.
        Ast.MethodDecl primitive = proxySurfaceMethod(Ast.TypeRef.simple("int"),
                List.of(), List.of());
        assertDoesNotThrow(() -> dev.oreslang.ast.StaticProxySynthesis.expand(
                proxySurfaceProgram(List.of(primitive)), Set.of("Foo")));
    }

    @Test
    void rejectsCallableMethodValuesBeforeForwarding() {
        Ast.TypeRef fnc = Ast.TypeRef.functionType(
                List.of(Ast.TypeRef.simple("int")), Ast.TypeRef.simple("int"));
        Ast.TypeRef alias = new Ast.TypeRef("Function",
                List.of(Ast.TypeRef.simple("int"), Ast.TypeRef.simple("int")), false);
        for (Ast.TypeRef callable : List.of(fnc, alias)) {
            Ast.MethodDecl method = proxySurfaceMethod(Ast.TypeRef.simple("void"),
                    List.of(new Ast.Param(callable, "callback")), List.of());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            proxySurfaceProgram(List.of(method)), Set.of("Foo")));
            assertTrue(error.getMessage().contains("reborrowing"), error.getMessage());
        }
    }

    @Test
    void rejectsErasedReturnsThatCouldDisguiseTheWrappedTarget() {
        for (String name : List.of("Any", "any", "Object", "object",
                "Unknown", "unknown", "Dynamic", "dynamic", "?")) {
            Ast.MethodDecl method = proxySurfaceMethod(Ast.TypeRef.simple(name),
                    List.of(), List.of());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            proxySurfaceProgram(List.of(method)), Set.of("Foo")));
            assertTrue(error.getMessage().contains("erase wrapped-target identity"),
                    name + ": " + error.getMessage());
        }
    }

    @Test
    void rejectsErasedReturnsInsideNestedGenericPayloads() {
        Ast.TypeRef nested = new Ast.TypeRef("Result", List.of(
                new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("Any")), false),
                Ast.TypeRef.simple("int")), false);
        Ast.MethodDecl method = proxySurfaceMethod(nested, List.of(), List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                        proxySurfaceProgram(List.of(method)), Set.of("Foo")));
        assertTrue(error.getMessage().contains("erase wrapped-target identity"),
                error.getMessage());
    }

    @Test
    void rejectsParametersShadowingGeneratedReceiverOrBackingStorage() {
        for (String reserved : List.of("self", "__proxy_target")) {
            Ast.MethodDecl method = proxySurfaceMethod(Ast.TypeRef.simple("int"),
                    List.of(new Ast.Param(Ast.TypeRef.simple("int"), reserved)), List.of());
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            proxySurfaceProgram(List.of(method)), Set.of("Foo")));
            assertTrue(error.getMessage().contains("shadows generated receiver/storage"),
                    reserved + ": " + error.getMessage());
        }
    }

    private static final String BEFORE_HOOK_SOURCE = """
            define class Foo as
              pub do_work(int value): int {
                return value + 1;
              }
            end
            define class Hook as
              pub before_do_work(int value): void {
                stdio.stdout.write(7);
                return;
              }
            end
            pub routine main(): void {
              val p = new Proxy(new Foo(), new Hook());
              stdio.stdout.write(p.do_work(41));
              return;
            }
            """;

    @Test
    void typedBeforeInterceptorExpandsIntoOrdinaryMethodsWithoutReflection() {
        Ast.Program parsed = Parser.parse(BEFORE_HOOK_SOURCE);
        Ast.ClassDecl generated = parsed.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl klass
                        && klass.name().equals("FooWithHookProxy"))
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        assertEquals(2, generated.fields().size());
        assertEquals(2, generated.constructor().arity());
        assertEquals("Foo", generated.constructor().parameters().get(0).type().name());
        assertEquals("Hook", generated.constructor().parameters().get(1).type().name());
        Ast.MethodDecl method = generated.methods().stream()
                .filter(m -> m.name().equals("do_work")).findFirst().orElseThrow();
        assertEquals(2, method.body().size());
        assertInstanceOf(Ast.ExprStmt.class, method.body().getFirst());
        Ast.CallExpr hook = (Ast.CallExpr) ((Ast.ExprStmt) method.body().getFirst()).expression();
        assertEquals("before_do_work", ((Ast.MemberExpr) hook.callee()).member());
        assertInstanceOf(Ast.ReturnStmt.class, method.body().get(1));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(BEFORE_HOOK_SOURCE));
    }

    @Test
    void typedBeforeInterceptorRunsBeforeExactlyOneTargetCall() throws Exception {
        assertEquals("742", run(BEFORE_HOOK_SOURCE));
    }

    @Test
    void beforeInterceptorConstructsOperandsOnceAndInSourceOrder() throws Exception {
        String source = """
                define class Foo as
                  constructor() {
                    stdio.stdout.write(1);
                  }
                  pub do_work(int value): int {
                    stdio.stdout.write(4);
                    return value + 1;
                  }
                end
                define class Hook as
                  constructor() {
                    stdio.stdout.write(2);
                  }
                  pub before_do_work(int value): void {
                    stdio.stdout.write(3);
                    return;
                  }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  stdio.stdout.write(p.do_work(41));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        // 1 = target constructor, 2 = hook constructor,
        // 3 = prehook, 4 = target invocation, 42 = returned value.
        assertEquals("123442", run(source));
    }

    @Test
    void beforeInterceptorsPreserveNameAndArityOverloadDispatch() throws Exception {
        String source = """
                define class Foo as
                  pub add(int value): int {
                    return value + 1;
                  }
                  pub add(int left, int right): int {
                    return left + right;
                  }
                end
                define class Hook as
                  pub before_add(int value): void {
                    stdio.stdout.write(1);
                    return;
                  }
                  pub before_add(int left, int right): void {
                    stdio.stdout.write(2);
                    return;
                  }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  stdio.stdout.write(p.add(10));
                  stdio.stdout.write(p.add(20, 21));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("111241", run(source));
    }

    @Test
    void beforeInterceptorsHandleVoidTargetWithoutDuplicateForwarding() throws Exception {
        String source = """
                define class Foo as
                  pub ping(): void {
                    stdio.stdout.write(2);
                    return;
                  }
                end
                define class Hook as
                  pub before_ping(): void {
                    stdio.stdout.write(1);
                    return;
                  }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  p.ping();
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("12", run(source));
    }

    @Test
    void repeatedTypedProxyInstancesShareClassButNotInvocations() throws Exception {
        String source = BEFORE_HOOK_SOURCE.replace(
                "stdio.stdout.write(p.do_work(41));",
                "stdio.stdout.write(p.do_work(41));\n"
                        + "  val q = new Proxy(new Foo(), new Hook());\n"
                        + "  stdio.stdout.write(q.do_work(42));");
        assertNotEquals(BEFORE_HOOK_SOURCE, source);
        Ast.Program program = Parser.parse(source);
        long generated = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("FooWithHookProxy")).count();
        assertEquals(1, generated, "multiple instances share one emitted type");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("742743", run(source), "each invocation runs its own before hook");
    }

    @Test
    void rejectsMissingOrWrongArityBeforeHook() {
        String src = BEFORE_HOOK_SOURCE.replace(
                "before_do_work(int value)", "before_do_work()");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(src));
        assertTrue(error.getMessage().contains("missing typed interceptor method"),
                error.getMessage());
    }

    @Test
    void rejectsPrivateOrWrongReturnBeforeHook() {
        for (String bad : List.of(
                "private before_do_work(int value): void",
                "pub before_do_work(int value): int")) {
            String src = BEFORE_HOOK_SOURCE.replace(
                    "pub before_do_work(int value): void", bad);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(src));
            assertTrue(error.getMessage().contains("interceptor method must"),
                    error.getMessage());
        }
    }

    @Test
    void rejectsBeforeHookParameterTypeMismatchAndExtraSlots() {
        String mismatched = BEFORE_HOOK_SOURCE.replace(
                "pub before_do_work(int value): void",
                "pub before_do_work(String value): void");
        IllegalArgumentException badSignature = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(mismatched));
        assertTrue(badSignature.getMessage().contains("interceptor parameter contract mismatch"),
                badSignature.getMessage());

        String extra = BEFORE_HOOK_SOURCE.replace(
                "pub before_do_work(int value): void {",
                "pub before_other(int value): void { return; }\n"
                        + "  pub before_do_work(int value): void {");
        assertNotEquals(BEFORE_HOOK_SOURCE, extra,
                "fixture must actually insert an extra before hook");
        IllegalArgumentException unknownSlot = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(extra));
        assertTrue(unknownSlot.getMessage().contains("does not match any forwarded public method"),
                unknownSlot.getMessage());
    }

    @Test
    void rejectsRuntimeInterceptorInferenceAndSyntheticNameCapture() {
        String runtime = BEFORE_HOOK_SOURCE.replace(
                "val p = new Proxy(new Foo(), new Hook());",
                "val h = new Hook();\nval p = new Proxy(new Foo(), h);");
        IllegalArgumentException inferred = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(runtime));
        assertTrue(inferred.getMessage().contains("statically known"),
                inferred.getMessage());
        String collision = BEFORE_HOOK_SOURCE.replace(
                "pub routine main(): void", "define class FooWithHookProxy as end\n"
                        + "pub routine main(): void");
        IllegalArgumentException capture = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(collision));
        assertTrue(capture.getMessage().contains("collides"), capture.getMessage());
    }

    @Test
    void rejectsBeforeHookReusingOwnedNominalArguments() {
        String source = """
                define class Payload as end
                define class Foo as
                  pub consume(Payload p): int { return 1; }
                end
                define class Hook as
                  pub before_consume(Payload p): void { return; }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("requires copyable scalar arguments"),
                error.getMessage());
    }

    @Test
    void rejectsUnprovenHookSuspensionInDirectAndNestedSourceForms() {
        Ast.TypeRef integer = Ast.TypeRef.simple("int");
        Ast.ClassDecl target = new Ast.ClassDecl("Foo", false, List.of(), List.of(),
                List.of(proxySurfaceMethod(integer,
                        List.of(new Ast.Param(integer, "value")), List.of())));
        Ast.Stmt directAwait = new Ast.ExprStmt(
                new Ast.AwaitExpr(new Ast.NameExpr("pending")));
        Ast.Stmt nestedAwait = new Ast.IfStmt(
                List.of(new Ast.IfBranch(new Ast.LiteralExpr(true),
                        List.of(directAwait))), List.of());
        Ast.Stmt taskScope = new Ast.TaskScopeStmt(List.of());
        for (Ast.Stmt forbidden : List.of(directAwait, nestedAwait, taskScope)) {
            Ast.MethodDecl hook = new Ast.MethodDecl("before_read",
                    Ast.Visibility.PUBLIC, false, false, false, false, false,
                    null, List.of(), List.of(new Ast.Param(integer, "value")),
                    Ast.TypeRef.simple("void"), List.of(),
                    List.of(forbidden, new Ast.ReturnStmt(null)));
            Ast.ClassDecl hooks = new Ast.ClassDecl("Hooks", false, List.of(),
                    List.of(), List.of(hook));
            Ast.Program program = new Ast.Program(List.of(
                    new Ast.ModuleDecl("first", List.of(target, hooks))));
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            program, Set.of(), List.of(
                                    new dev.oreslang.ast.StaticProxySynthesis.InterceptorRequest(
                                            "Foo", "Hooks"))));
            assertTrue(error.getMessage().contains("unproven suspension"),
                    error.getMessage());
        }
    }

    @Test
    void beforeHookTraversesKnownSynchronousLocalHelperChain() throws Exception {
        String source = """
                define class Foo as
                  pub do_work(int value): int {
                    return value + 1;
                  }
                end
                define class Hook as
                  private emit_value(int value): void {
                    stdio.stdout.write(value);
                    return;
                  }
                  private forward_value(int value): void {
                    self.emit_value(value);
                    return;
                  }
                  pub before_do_work(int value): void {
                    self.forward_value(value);
                    return;
                  }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  stdio.stdout.write(p.do_work(41));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("4142", run(source));
    }

    @Test
    void rejectsSuspensionReachedThroughAnOtherwiseSynchronousHelper() {
        String source = """
                define class Foo as
                  pub do_work(int value): int { return value + 1; }
                end
                define class Hook as
                  private dangerous(int value): void {
                    await value;
                    return;
                  }
                  pub before_do_work(int value): void {
                    self.dangerous(value);
                    return;
                  }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("unverified") ||
                error.getMessage().contains("unproven"), error.getMessage());
    }

    @Test
    void rejectsRecursiveAndUnresolvedHelperDispatchBeforeTypeChecking() {
        String source = """
                define class Foo as
                  pub do_work(int value): int { return value + 1; }
                end
                define class Hook as
                  private helper(int value): void {
                    self.helper(value);
                    return;
                  }
                  pub before_do_work(int value): void {
                    self.helper(value);
                    return;
                  }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  return;
                }
                """;
        IllegalArgumentException cycle = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(cycle.getMessage().contains("recursive interceptor helper dispatch"),
                cycle.getMessage());
        String missing = source.replace("self.helper(value);", "self.not_found(value);");
        IllegalArgumentException unresolved = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(missing));
        assertTrue(unresolved.getMessage().contains("unresolved local slot"),
                unresolved.getMessage());
        String freeCall = source.replace("self.helper(value);", "untrusted(value);");
        IllegalArgumentException indirect = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(freeCall));
        assertTrue(indirect.getMessage().contains("unresolved/indirect dispatch"),
                indirect.getMessage());
    }

    @Test
    void rejectsBeforeHookStdioNamespaceShadowing() {
        String source = """
                define class Foo as
                  pub do_work(int value): int { return value + 1; }
                end
                define class Hook as
                  val int stdio;
                  pub before_do_work(int value): void {
                    stdio.stdout.write(value);
                    return;
                  }
                end
                pub routine main(): void {
                  val p = new Proxy(new Foo(), new Hook());
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("interceptor field conflicts"),
                error.getMessage());
    }

    @Test
    void rejectsUnsafeOperatorsAndOpaqueLiteralPayloadInHookBody() {
        Ast.TypeRef integer = Ast.TypeRef.simple("int");
        Ast.ClassDecl target = new Ast.ClassDecl("Foo", false, List.of(), List.of(),
                List.of(proxySurfaceMethod(integer,
                        List.of(new Ast.Param(integer, "value")), List.of())));
        for (Ast.Expr unverified : List.of(
                new Ast.BinaryExpr("|>", new Ast.NameExpr("value"),
                        new Ast.LiteralExpr(1)),
                new Ast.UnaryExpr("take!", new Ast.NameExpr("value")),
                new Ast.LiteralExpr(new Object()))) {
            Ast.MethodDecl hook = new Ast.MethodDecl("before_read",
                    Ast.Visibility.PUBLIC, false, false, false, false, false,
                    null, List.of(), List.of(new Ast.Param(integer, "value")),
                    Ast.TypeRef.simple("void"), List.of(),
                    List.of(new Ast.ExprStmt(unverified), new Ast.ReturnStmt(null)));
            Ast.ClassDecl hooks = new Ast.ClassDecl("Hooks", false, List.of(),
                    List.of(), List.of(hook));
            Ast.Program program = new Ast.Program(List.of(
                    new Ast.ModuleDecl("first", List.of(target, hooks))));
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            program, Set.of(), List.of(
                                    new dev.oreslang.ast.StaticProxySynthesis.InterceptorRequest(
                                            "Foo", "Hooks"))));
            assertTrue(error.getMessage().contains("unverified effects")
                    || error.getMessage().contains("not a recognized scalar"),
                    error.getMessage());
        }
    }

    @Test
    void repeatedIdenticalInterceptorRequestsReuseOneGeneratedClass() {
        Ast.TypeRef integer = Ast.TypeRef.simple("int");
        Ast.ClassDecl target = new Ast.ClassDecl("Foo", false, List.of(), List.of(),
                List.of(proxySurfaceMethod(integer,
                        List.of(new Ast.Param(integer, "value")), List.of())));
        Ast.MethodDecl hook = new Ast.MethodDecl("before_read", Ast.Visibility.PUBLIC,
                false, false, false, false, false, null, List.of(),
                List.of(new Ast.Param(integer, "value")), Ast.TypeRef.simple("void"),
                List.of(), List.of(new Ast.ReturnStmt(null)));
        Ast.ClassDecl hooks = new Ast.ClassDecl("Hooks", false, List.of(), List.of(),
                List.of(hook));
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("first", List.of(target, hooks))));
        var request = new dev.oreslang.ast.StaticProxySynthesis.InterceptorRequest(
                "Foo", "Hooks");
        Ast.Program expanded = assertDoesNotThrow(() ->
                dev.oreslang.ast.StaticProxySynthesis.expand(
                        program, Set.of(), List.of(request, request, request)));
        long generated = expanded.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("FooWithHooksProxy")).count();
        assertEquals(1, generated, "identical proxy instances share the AOT class");
    }

    @Test
    void rejectsNamespaceAndProtectedDeclarationSpoofingBuiltinStdio() {
        Ast.TypeRef integer = Ast.TypeRef.simple("int");
        Ast.ClassDecl target = new Ast.ClassDecl("Foo", false, List.of(), List.of(),
                List.of(proxySurfaceMethod(integer,
                        List.of(new Ast.Param(integer, "value")), List.of())));
        Ast.MethodDecl hook = new Ast.MethodDecl("before_read", Ast.Visibility.PUBLIC,
                false, false, false, false, false, null, List.of(),
                List.of(new Ast.Param(integer, "value")), Ast.TypeRef.simple("void"),
                List.of(), List.of(new Ast.ReturnStmt(null)));
        Ast.ClassDecl hooks = new Ast.ClassDecl("Hooks", false, List.of(), List.of(),
                List.of(hook));
        var request = new dev.oreslang.ast.StaticProxySynthesis.InterceptorRequest(
                "Foo", "Hooks");
        Ast.Program shadowedNamespace = new Ast.Program("stdio", List.of(), List.of(
                new Ast.ModuleDecl("first", List.of(target, hooks))));
        Ast.Program shadowedContainer = new Ast.Program(List.of(
                new Ast.ModuleDecl("first", List.of(target, hooks,
                        new Ast.StateContainerDecl("stdio", Ast.Visibility.PRIVATE,
                                List.of(), List.of())))));
        Ast.Program shadowedVirtualActor = new Ast.Program(List.of(
                new Ast.ModuleDecl("first", List.of(target, hooks,
                        new Ast.VirtualActorDecl("stdio", Ast.Visibility.PRIVATE,
                                List.of(), List.of())))));
        for (Ast.Program program : List.of(
                shadowedNamespace, shadowedContainer, shadowedVirtualActor)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.ast.StaticProxySynthesis.expand(
                            program, Set.of(), List.of(request)));
            assertTrue(error.getMessage().contains("stdio")
                    && error.getMessage().contains("shadowed"), error.getMessage());
        }
    }

    @Test
    void twoBeforeHooksCanShareOneStaticallyCheckedHelperDag() throws Exception {
        String source = """
                define class Foo as
                  pub read(int value): int { return value + 1; }
                  pub write(int value): int { return value + 2; }
                end
                define class Hooks as
                  private trace(int value): void {
                    stdio.stdout.write(value);
                    return;
                  }
                  pub before_read(int value): void {
                    self.trace(value);
                    return;
                  }
                  pub before_write(int value): void {
                    self.trace(value);
                    return;
                  }
                end
                pub routine main(): void {
                  val proxy = new Proxy(new Foo(), new Hooks());
                  stdio.stdout.write(proxy.read(40));
                  stdio.stdout.write(proxy.write(40));
                  return;
                }
                """;
        Ast.Program parsed = Parser.parse(source);
        Ast.ClassDecl generated = parsed.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("FooWithHooksProxy"))
                .map(decl -> (Ast.ClassDecl) decl)
                .findFirst().orElseThrow();
        assertEquals(2, generated.methods().size());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("40414042", run(source));
    }

    @Test
    void rejectsOversizedInterceptorHelperMethodTablesBeforeGraphTraversal() {
        Ast.TypeRef integer = Ast.TypeRef.simple("int");
        Ast.ClassDecl target = new Ast.ClassDecl("Foo", false, List.of(), List.of(),
                List.of(proxySurfaceMethod(integer,
                        List.of(new Ast.Param(integer, "value")), List.of())));
        List<Ast.MethodDecl> methods = new java.util.ArrayList<>();
        methods.add(new Ast.MethodDecl("before_read", Ast.Visibility.PUBLIC,
                false, false, false, false, false, null, List.of(),
                List.of(new Ast.Param(integer, "value")), Ast.TypeRef.simple("void"),
                List.of(), List.of(new Ast.ReturnStmt(null))));
        for (int i = 0; i < 4096; i++) {
            methods.add(new Ast.MethodDecl("helper_" + i, Ast.Visibility.PRIVATE,
                    false, false, false, false, false, null, List.of(),
                    List.of(), Ast.TypeRef.simple("void"), List.of(),
                    List.of(new Ast.ReturnStmt(null))));
        }
        Ast.ClassDecl hooks = new Ast.ClassDecl("Hooks", false, List.of(), List.of(),
                methods);
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl("first", List.of(target, hooks))));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () ->
                        dev.oreslang.ast.StaticProxySynthesis.expand(
                                program, Set.of(), List.of(
                                        new dev.oreslang.ast.StaticProxySynthesis.InterceptorRequest(
                                                "Foo", "Hooks"))));
        assertTrue(error.getMessage().contains("method table exceeds slot budget"),
                error.getMessage());
    }

    private static Ast.Program proxySurfaceProgram(List<Ast.MethodDecl> methods) {
        Ast.ClassDecl klass = new Ast.ClassDecl("Foo", false, List.of(), List.of(), methods);
        return new Ast.Program(List.of(new Ast.ModuleDecl("first", List.of(klass))));
    }

    private static Ast.MethodDecl proxySurfaceMethod(Ast.TypeRef result,
            List<Ast.Param> parameters, List<Ast.Annotation> annotations) {
        return new Ast.MethodDecl("read", Ast.Visibility.PUBLIC,
                false, false, false, false, false, null, List.of(),
                parameters, result, annotations, List.of());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "aot-class-proxy.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

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
    void compilerSynthesizedProxiesPreserveFileLevelExports() {
        String source = """
                export pub Foo;
                define pub class Foo as
                  pub answer(): int { return 42; }
                end
                pub routine main(): void {
                  val proxy = new Proxy(new Foo());
                  return;
                }
                """;
        Ast.Program parsed = Parser.parse(source);
        assertEquals(1, parsed.exports().size(), "file-level exports must not disappear");
        assertTrue(parsed.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .anyMatch(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("FooProxy")));
    }

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

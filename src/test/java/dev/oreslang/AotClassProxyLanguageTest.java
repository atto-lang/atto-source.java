package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "aot-class-proxy.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

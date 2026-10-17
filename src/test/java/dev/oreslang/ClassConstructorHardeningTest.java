package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ClassConstructorHardeningTest {

    @TempDir
    Path temp;

    @Test
    void constructorIsAFirstClassClassMemberWithModuleVisibility() {
        Ast.Program program = Parser.parse("""
                define module model as
                  pub define class Box as
                    val int value;

                    pub constructor(int value) {
                      self.value = value;
                    }

                    pub get(): int {
                      return self.value;
                    }
                  end
                end
                """);

        Ast.ModuleDecl module = program.modules().stream()
                .filter(candidate -> candidate.name().equals("model"))
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl box = (Ast.ClassDecl) module.declarations().getFirst();

        assertEquals(Ast.Visibility.PUBLIC, box.visibility());
        assertNotNull(box.constructor());
        assertEquals(Ast.Visibility.PUBLIC, box.constructor().visibility());
        assertEquals(1, box.constructor().arity());
        assertEquals("value", box.constructor().parameters().getFirst().name());
    }

    @Test
    void explicitConstructorInitializesImmutableStorageExactlyOnce() throws Exception {
        String output = run("""
                define class Box as
                  val int value;

                  constructor(int value) {
                    self.value = value;
                  }

                  pub get(): int {
                    return self.value;
                  }
                end

                pub routine main(): void {
                  val box = new Box(42);
                  stdio.stdout.write(box.get());
                  return;
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void overloadsResolveByArityNotParameterTypes() throws Exception {
        String source = """
                define class Sample as
                  val int value;

                  constructor() { self.value = 1; return; }
                  constructor(int n) { self.value = n; return; }
                  constructor(int n, int extra) { self.value = n + extra; return; }

                  pub get(): int { return self.value; }
                end
                pub routine main(): void {
                  val a = new Sample();
                  val b = new Sample(20);
                  val c = new Sample(20, 22);
                  stdio.stdout.write(a.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(c.get());
                  return;
                }
                """;
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        assertEquals(3, klass.constructors().size());
        assertEquals(0, klass.constructorForArity(0).arity());
        assertEquals(2, klass.constructorForArity(2).arity());
        assertNull(klass.constructorForArity(3));
        assertEquals("1|20|42", run(source));
    }

    @Test
    void constructorValBranchInitializationExecutesWithBothOutcomes() throws Exception {
        String output = run("""
                define class Counter as
                    val int count;
                    constructor(int n) {
                        if n > 0 then
                            self.count = n;
                        else
                            self.count = 0;
                        fi
                        return;
                    }
                    pub get(): int { return self.count; }
                end
                pub routine main(): void {
                    val first = new Counter(-1);
                    val second = new Counter(42);
                    stdio.stdout.write(first.get());
                    stdio.stdout.write("|");
                    stdio.stdout.write(second.get());
                    return;
                }
                """);
        assertEquals("0|42", output);
    }

    @Test
    void inferredGenericConstructorUsesSelectedArity() throws Exception {
        String source = """
                define class Holder<T> as
                  val T value;
                  constructor(T initial) { self.value = initial; return; }
                  constructor(T initial, int offset) { self.value = initial; return; }
                  pub get(): T { return self.value; }
                end
                pub routine main(): void {
                  val holder = new Holder<>(42, 1);
                  stdio.stdout.write(holder.get());
                  return;
                }
                """;
        assertEquals("42", run(source));
    }

    @Test
    void missingConstructorArityRejectsRatherThanUsingSyntheticFields() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                define class Sample as
                  val int value;
                  constructor() { self.value = 42; return; }
                  constructor(int n, int extra) { self.value = n + extra; return; }
                end
                pub routine main(): void {
                  val x = new Sample(42);
                  return;
                }
                """));
        assertTrue(String.valueOf(failure).contains("constructor")
                || String.valueOf(failure.getCause()).contains("constructor"));
    }

    @Test
    void overloadedConstructorFieldInitializersMustBeValidForEveryArity() {
        assertThrows(Exception.class, () -> run("""
                define class Sample as
                  val int value: input;
                  constructor(int input) { return; }
                  constructor() { return; }
                end
                pub routine main(): void {
                  val x = new Sample();
                  return;
                }
                """));
    }

    @Test
    void omittedConstructorKeepsLegacySynthesizedFieldInitialization() throws Exception {
        String output = run("""
                define class Legacy as
                  val int value;
                  val int offset = 2;

                  pub total(): int {
                    return self.value + self.offset;
                  }
                end

                pub routine main(): void {
                  val legacy = new Legacy(40);
                  stdio.stdout.write(legacy.total());
                  return;
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void parserRejectsJavaStyleDuplicateNestedAndTypedConstructors() {
        IllegalArgumentException javaStyle = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                          Box(int value) {
                            return;
                          }
                        end
                        """));
        assertTrue(javaStyle.getMessage().contains("constructor(...)"));
        assertTrue(javaStyle.getMessage().contains("not the class name"));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                          constructor(int value) { return; }
                          constructor(string text) { return; }
                        end
                        """));
        assertTrue(duplicate.getMessage().contains("duplicate constructor arity 1"));

        IllegalArgumentException typed = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                          constructor(): void {
                            return;
                          }
                        end
                        """));
        assertTrue(typed.getMessage().contains("do not declare a return type"));

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Outer as
                          define class Inner as
                          end
                        end
                        """));
        assertTrue(nested.getMessage().contains("cannot be nested inside classes"));
    }

    @Test
    void fileRootAndModuleClassVisibilityIsExplicit() {
        Ast.Program rootProgram = Parser.parse("""
                pub define class Exported as
                  pub constructor() { return; }
                end

                define class Hidden as
                end
                """);
        Ast.ModuleDecl root = rootProgram.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl rootExported = (Ast.ClassDecl) root.declarations().get(0);
        Ast.ClassDecl rootHidden = (Ast.ClassDecl) root.declarations().get(1);
        assertEquals(Ast.Visibility.PUBLIC, rootExported.visibility());
        assertEquals(Ast.Visibility.PUBLIC, rootExported.constructor().visibility());
        assertEquals(Ast.Visibility.PRIVATE, rootHidden.visibility());

        IllegalArgumentException publicConstructorOnPrivateClass = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module model as
                          define class Hidden as
                            pub constructor() { return; }
                          end
                        end
                        """));
        assertTrue(publicConstructorOnPrivateClass.getMessage().contains("public constructor requires a public class"));

        Ast.Program program = Parser.parse("""
                define module model as
                  define class Hidden as
                  end

                  pub define class Exported as
                    private constructor() { return; }
                  end
                end
                """);
        Ast.ModuleDecl model = program.modules().stream()
                .filter(module -> module.name().equals("model"))
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl hidden = (Ast.ClassDecl) model.declarations().get(0);
        Ast.ClassDecl exported = (Ast.ClassDecl) model.declarations().get(1);
        assertEquals(Ast.Visibility.PRIVATE, hidden.visibility());
        assertEquals(Ast.Visibility.PUBLIC, exported.visibility());
        assertEquals(Ast.Visibility.PRIVATE, exported.constructor().visibility());
    }

    @Test
    void constructorMustInitializeEveryRequiredFieldAndCannotRewriteImmutableFields() {
        PolyglotException missing = assertThrows(
                PolyglotException.class,
                () -> run("""
                        define class Broken as
                          val int value;
                          constructor() {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val broken = new Broken();
                          return;
                        }
                        """));
        assertTrue(missing.getMessage().contains("did not initialize field 'value'"));

        PolyglotException twice = assertThrows(
                PolyglotException.class,
                () -> run("""
                        define class Broken as
                          val int value;
                          constructor(int value) {
                            self.value = value;
                            self.value = value + 1;
                          }
                        end

                        pub routine main(): void {
                          val broken = new Broken(1);
                          return;
                        }
                        """));
        assertTrue(twice.getMessage().contains("immutable"));
    }

    @Test
    void explicitParentConstructorsRequireFutureSuperChaining() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent as
                          constructor() {
                            return;
                          }
                        end

                        define class Child extends Parent as
                        end
                        """)));
        assertTrue(failure.getMessage().contains("super-constructor chaining"));
    }

    @Test
    void crossFileConstructionRequiresPublicModuleClassAndPublicConstructor() throws Exception {
        Path library = temp.resolve("library.ores");
        Files.writeString(library, """
                define module model as
                  pub define class PublicBox as
                    val int value;

                    pub constructor(int value) {
                      self.value = value;
                    }

                    pub get(): int {
                      return self.value;
                    }
                  end

                  pub define class FactoryOnly as
                    val int value = 1;

                    private constructor() {
                      return;
                    }
                  end

                  define class HiddenBox as
                    val int value = 2;
                  end
                end
                """);

        Path publicMain = temp.resolve("public-main.ores");
        Files.writeString(publicMain, """
                import class PublicBox from "./library.ores";

                pub routine main(): void {
                  val box = new PublicBox(42);
                  stdio.stdout.write(box.get());
                  return;
                }
                """);
        assertEquals("42", runLinked(publicMain));

        Path privateConstructorMain = temp.resolve("private-constructor-main.ores");
        Files.writeString(privateConstructorMain, """
                import class FactoryOnly from "./library.ores";

                pub routine main(): void {
                  val value = new FactoryOnly();
                  return;
                }
                """);
        Exception privateConstructor = assertThrows(
                Exception.class,
                () -> runLinked(privateConstructorMain));
        assertTrue(privateConstructor.getMessage().contains("constructor")
                || (privateConstructor.getCause() != null
                    && String.valueOf(privateConstructor.getCause().getMessage()).contains("constructor")));

        Path privateClassMain = temp.resolve("private-class-main.ores");
        Files.writeString(privateClassMain, """
                import class HiddenBox from "./library.ores";

                pub routine main(): void {
                  val value = new HiddenBox();
                  return;
                }
                """);
        Exception privateClass = assertThrows(
                Exception.class,
                () -> runLinked(privateClassMain));
        assertTrue(privateClass.getMessage().contains("public ordinary class")
                || privateClass.getMessage().contains("export")
                || (privateClass.getCause() != null
                    && String.valueOf(privateClass.getCause().getMessage()).contains("export")));
    }

    @Test
    void explicitConstructorSelfMarkerIsNotCallerVisibleArity() throws Exception {
        String source = """
                define class Sample as
                  val int value;

                  constructor(self, int value) {
                    self.value = value;
                    return;
                  }

                  constructor(self, int value, string ignored) {
                    self.value = value + 2;
                    return;
                  }

                  pub get(): int { return self.value; }
                end

                pub routine main(): void {
                  val one = new Sample(40);
                  val two = new Sample(40, "ignored");
                  stdio.stdout.write(one.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(two.get());
                  return;
                }
                """;

        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
        assertEquals(List.of(1, 2),
                klass.constructors().stream().map(Ast.ConstructorDecl::arity).toList());
        assertEquals("40|42", run(source));
    }

    @Test
    void explicitConstructorSelfMarkerCollidesWithImplicitReceiverAtSameArity() {
        IllegalArgumentException one = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Sample as
                          constructor(int value) { return; }
                          constructor(self, string value) { return; }
                        end
                        """));
        assertTrue(one.getMessage().contains("duplicate constructor arity 1"));

        IllegalArgumentException zero = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Sample as
                          constructor() { return; }
                          constructor(self) { return; }
                        end
                        """));
        assertTrue(zero.getMessage().contains("duplicate constructor arity 0"));
    }

    @Test
    void constructorCallbackCannotEscapePartiallyInitializedSelfThroughClosure() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                define class Sample as
                  let int dummy_var: 1;

                  constructor(self, int foo, ((AnyCallable cb) => void) cb) {
                    cb(|| -> {
                      self.dummy_var = 1 + foo;
                      return;
                    });
                    return;
                  }
                end

                pub routine main(): void {
                  return;
                }
                """));
        String message = String.valueOf(failure)
                + " " + String.valueOf(failure.getCause());
        assertTrue(message.contains("self") || message.contains("borrow"));
    }

    @Test
    void constructorMayCaptureSelfInInvocationScopedBorrowedCallback() throws Exception {
        String output = run("""
                fnc invoke_now(borrow (() => void) cb): void {
                  cb();
                  return;
                }

                define class Sample as
                  let int dummy_var: 1;

                  constructor(self, int foo, ((borrow (() => void) cb) => void) runner) {
                    runner(|| -> {
                      self.dummy_var = 1 + foo;
                      return;
                    });
                    return;
                  }
                end

                pub routine main(): void {
                  invoke_now(|| -> {
                    stdio.stdout.write("ok");
                    return;
                  });
                  return;
                }
                """);
        assertEquals("ok", output);
    }

    @Test
    void borrowedCallableCannotEscapeAsOwnedCallable() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc leak(borrow (() => void) cb): (() => void) {
                  return cb;
                }

                pub routine main(): void {
                  return;
                }
                """));
        String message = String.valueOf(failure)
                + " " + String.valueOf(failure.getCause());
        assertTrue(message.contains("borrow")
                || message.contains("expected Function")
                || message.contains("return value"));
    }

    @Test
    void namedCallableRequiresExplicitBorrowForBorrowedCallableParameter() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                fnc invoke_now(borrow (() => void) cb): void {
                  cb();
                  return;
                }

                fnc target(): void {
                  return;
                }

                pub routine main(): void {
                  invoke_now(target);
                  return;
                }
                """));
        String message = String.valueOf(failure)
                + " " + String.valueOf(failure.getCause());
        assertTrue(message.contains("borrow")
                || message.contains("argument 1"));
    }

    @Test
    void borrowedCallbackCannotEstablishBlankValInitialization() {
        Exception failure = assertThrows(Exception.class, () -> run("""
                define class Sample as
                  val int value;

                  constructor(self, ((borrow (() => void) cb) => void) runner) {
                    runner(|| -> {
                      self.value = 42;
                      return;
                    });
                    return;
                  }
                end

                pub routine main(): void {
                  return;
                }
                """));
        String message = String.valueOf(failure)
                + " " + String.valueOf(failure.getCause());
        assertTrue(message.contains("directly in a constructor statement")
                || message.contains("initialize field 'value'"));
    }

    @Test
    void actorsRejectClassConstructors() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        actor Worker {
                          constructor() {
                            return;
                          }
                        }
                        """));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "class-constructor-hardening.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String runLinked(Path entry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}

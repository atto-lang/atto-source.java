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
    void synthesizedConstructorCannotReplaceInitializedFinalSlot() throws Exception {
        String bad = """
                define class Fixed as
                  final int answer: 42;
                end
                pub routine main(): void {
                  val value = new Fixed(7);
                  return;
                }
                """;
        IllegalArgumentException typeFailure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(bad)));
        assertTrue(typeFailure.getMessage().contains(
                "positional construction cannot override initialized final class field"));

        PolyglotException runtimeFailure = assertThrows(PolyglotException.class,
                () -> run(bad));
        assertTrue(runtimeFailure.getMessage().contains(
                "positional construction cannot override initialized final class field"));

        String okay = """
                define class Fixed as
                  final int answer: 42;
                  pub get(): int {
                    return self.answer;
                  }
                end
                pub routine main(): void {
                  val value = new Fixed();
                  stdio.stdout.write(value.get());
                  return;
                }
                """;
        assertEquals("42", run(okay));
    }

    @Test
    void synthesizedConstructorCannotReplaceInitializedLegacyValSlot() {
        IllegalArgumentException typeFailure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    define class Fixed as
                      val int answer = 42;
                    end
                    pub fnc main(): int {
                      val Fixed value = new Fixed(7);
                      return 0;
                    }
                    """)));
        assertTrue(typeFailure.getMessage().contains(
                "positional construction cannot override initialized final class field"));
    }

    @Test
    void finalDeclarationCanUseConstructorParametersBeforeConstructorBody() throws Exception {
        String source = """
                define class User as
                  final int age: supplied_age + 1;
                  final int doubleAge: supplied_age * 2;

                  constructor(int supplied_age) {
                    return;
                  }

                  pub total(): int {
                    return self.age + self.doubleAge;
                  }
                end

                pub routine main(): void {
                  val user = new User(10);
                  stdio.stdout.write(user.total());
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("31", run(source));
    }

    @Test
    void immutableStringConstructorParameterCanInitializeSeveralFixedFields() throws Exception {
        String source = """
                define class Greeting as
                  final string first: supplied;
                  final string second: supplied;
                  constructor(string supplied) { return; }
                  pub get(): string {
                    return self.first + self.second;
                  }
                end
                pub routine main(): void {
                  val greeting = new Greeting("Hi");
                  stdio.stdout.write(greeting.get());
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("HiHi", run(source));
    }

    @Test
    void exactNativeScalarConstructorGuestMatchesJavaResult() throws Exception {
        // Byte-for-byte source contract with LLVM fixture:
        // tests/fixtures/class-scalar-constructor-arithmetic-native.ores
        String sourceText = """
                define class Pair as
                  pub final int a: start + 1;
                  pub final mut int b: start * 2;
                  constructor(int start) { return; }
                end
                pub fnc main(): int {
                  let Pair p = new Pair(13);
                  return p.a + p.b;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sourceText)));
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "scalar-constructor-parity.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            assertEquals(40, context.eval(source).asInt());
        }
    }

    @Test
    void finalDeclarationRejectsMissingConstructorParameter() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    define class Missing as
                      final int field: undeclared_argument;
                      constructor(int actual_argument) { return; }
                    end
                    """)));
        assertTrue(failure.getMessage().contains("undeclared_argument"));
    }

    @Test
    void referenceValuedConstructorArgumentCannotEnterFieldInitializerWithoutOwnershipProof() {
        String bad = """
                define class Resource as
                  final int id: 1;
                end
                define class Holder as
                  final Resource resource: supplied_resource;
                  constructor(Resource supplied_resource) { return; }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(bad)));
        assertTrue(failure.getMessage().contains("supplied_resource"));
    }

    @Test
    void implicitConstructorArgumentsTupleCannotBypassFieldInitializerAdmission() {
        String bad = """
                define class Resource as
                  final int id: 1;
                end
                define class Holder as
                  final Resource resource: arguments[0];
                  constructor(Resource supplied_resource) { return; }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(bad)));
    }

    @Test
    void constructorCannotOverwriteDeclarationInitializedParameterField() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    define class Unsafe as
                      final int field: supplied_value;
                      constructor(int supplied_value) {
                        self.field = supplied_value + 1;
                      }
                    end
                    """)));
        assertTrue(failure.getMessage().contains("cannot reassign final class field"));
    }

    @Test
    void constructorCannotDispatchInstanceMethodBeforeObjectPublication() {
        String sourceText = """
                define class Unsafe as
                  val int value;
                  constructor(int input) {
                    self.publish();
                    self.value = input;
                  }
                  pub publish(): void {
                    stdio.stdout.write(self.value);
                    return;
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(sourceText)));
        assertTrue(error.getMessage().contains(
                "constructor cannot invoke methods on partially initialized self"));
    }

    @Test
    void constructorCannotCopyOrSharePartiallyInitializedSelf() {
        for (String operation : java.util.List.of("copy", "share", "take")) {
            String sourceText = """
                    define class Unsafe as
                      val int age;
                      constructor(int age) {
                        const leaked = rt %s self;
                        self.age = age;
                      }
                    end
                    """.formatted(operation);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(sourceText)));
            assertTrue(error.getMessage().contains(
                    "constructor cannot apply runtime operation to partially initialized self"));
        }
    }

    @Test
    void constructorCannotCreateBorrowAliasToPartiallyInitializedSelf() {
        String unary = """
                define class Unsafe as
                  val int age;
                  constructor(int age) {
                    const alias = &self;
                    self.age = age;
                  }
                end
                """;
        IllegalArgumentException unaryFailure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(unary)));
        assertTrue(unaryFailure.getMessage().contains(
                "constructor cannot borrow partially initialized self"), unaryFailure.getMessage());

        String runtimeIntrinsic = """
                define class Unsafe as
                  val int age;
                  constructor(int age) {
                    const alias = rt borrow self;
                    self.age = age;
                  }
                end
                """;
        IllegalArgumentException runtimeFailure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(runtimeIntrinsic)));
        assertTrue(runtimeFailure.getMessage().contains(
                "constructor cannot borrow partially initialized self"), runtimeFailure.getMessage());
    }

    @Test
    void constructorCannotLogOrBorrowPartiallyInitializedSelf() {
        String logger = """
                define class Unsafe as
                  val int age;
                  constructor(int age) {
                    stdio.stdout.log(self);
                    self.age = age;
                  }
                end
                """;
        IllegalArgumentException logging = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(logger)));
        assertTrue(logging.getMessage().contains(
                "constructor cannot pass partially initialized self to a call"));

        String borrowed = """
                define class Unsafe as
                  val int age;
                  constructor(int age) {
                    stdio.stdout.log(&self);
                    self.age = age;
                  }
                end
                """;
        IllegalArgumentException borrowing = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(borrowed)));
        assertTrue(borrowing.getMessage().contains(
                "constructor cannot borrow partially initialized self for a call"));
    }

    @Test
    void partiallyInitializedConstructorSelfCannotBeTransferredAsValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                    define class Unsafe as
                      val int age;
                      constructor(int value) {
                        const leaked = self;
                        self.age = value;
                      }
                    end
                    """)));
        assertTrue(error.getMessage().contains(
                "partially initialized constructor self cannot escape"));
    }

    @Test
    void fieldInitializerCannotSuspendEvenInsideAnArithmeticExpression() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    define class Unsafe as
                      final int value: 1 + (await someFuture());
                    end
                    """)));
        assertTrue(failure.getMessage().contains(
                "cannot await, select, call, or suspend during object construction"));
    }

    @Test
    void fieldInitializerCannotInvokeUncheckedTransitiveFunction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    fnc helper(): int {
                      stdio.stdout.write("side effect");
                      return 42;
                    }
                    define class Unsafe as
                      final int value: helper();
                    end
                    """)));
        assertTrue(error.getMessage().contains("without a proven transitive effect bound"));
    }

    @Test
    void nestedClassConstructionNeedsVerifiedLiteralOnlyDefaultInitialization() {
        String safe = """
                define class Inner as
                  final int answer: 42;
                end
                define class Outer as
                  final mut Inner inner: new Inner();
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(safe)));

        String unsafe = """
                define class Inner as
                  final int answer: 42;
                  constructor() { return; }
                end
                define class Outer as
                  final mut Inner inner: new Inner();
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(unsafe)));
        assertTrue(failure.getMessage().contains("without a proven transitive effect bound"));
    }

    @Test
    void fieldInitializerCannotCapturePartiallyInitializedSelf() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                    define class Unsafe as
                      final int a: 1;
                      final int b: self.a + 1;
                    end
                    """)));
        assertTrue(failure.getMessage().contains("self"));
    }

    @Test
    void ordinaryClassFieldInitializersAreOwnershipChecked() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Good as
                  final int answer: 40 + 2;
                  constructor() { return; }
                end
                """)));
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
                          constructor() { return; }
                          constructor(int value) { return; }
                        end
                        """));
        assertTrue(duplicate.getMessage().contains("only one constructor"));

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

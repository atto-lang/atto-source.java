package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StaticClassContractHardeningTest {

    @Test
    void staticContractRequiresStaticNamespaceShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define static contract Factory as
                  version: string;
                  fnc create(int id) => int;
                end

                define class Widget implements static Factory as
                  pub static const version: string = "1";
                  pub static fnc create(int id): int { return id; }
                end
                """)));
    }

    @Test
    void instanceMembersCannotSatisfyStaticContract() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define static contract Factory as
                          version: string;
                          fnc create(int id) => int;
                        end

                        define class Widget implements static Factory as
                          pub const version: string = "1";
                          pub fnc create(int id): int { return id; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("static contract"));
    }

    @Test
    void staticMembersCannotSatisfyInstanceInterface() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Factory as
                          fnc create(int id) => int;
                        end

                        define class Widget implements Factory as
                          pub static fnc create(int id): int { return id; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("does not implement interface"));
    }

    @Test
    void wrongContractSideGetsTargetedDiagnostics() {
        IllegalArgumentException missingStatic = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define static contract Factory as
                          fnc create(): int;
                        end
                        define class Widget implements Factory as
                          pub static fnc create(): int { return 1; }
                        end
                        """)));
        assertTrue(missingStatic.getMessage().contains("implements static Factory"));

        IllegalArgumentException moduleUsesStatic = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define static contract Factory as
                          fnc create(): int;
                        end
                        define module widgets conforms Factory as
                          pub fnc create(): int { return 1; }
                        end
                        """)));
        assertTrue(moduleUsesStatic.getMessage().contains("cannot be used by a module"));

        IllegalArgumentException classUsesModule = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define contract Factory as
                          fnc create(): int;
                        end
                        define class Widget implements static Factory as
                          pub static fnc create(): int { return 1; }
                        end
                        """)));
        assertTrue(classUsesModule.getMessage().contains("module contract"));
    }

    @Test
    void contractInheritanceCannotCrossDeclarationKind() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define static contract Named as
                  name: string;
                end
                define static contract Factory extends Named as
                  fnc create(): int;
                end
                define class Widget implements static Factory as
                  pub static const name: string = "widget";
                  pub static fnc create(): int { return 1; }
                end
                """)));

        IllegalArgumentException crossKind = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define contract ModuleApi as
                          fnc ping(): void;
                        end
                        define static contract Bad extends ModuleApi as
                          fnc create(): int;
                        end
                        """)));
        assertTrue(crossKind.getMessage().contains("declaration categories"));
    }

    @Test
    void readableFieldDoesNotGrantWriteAuthority() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define static contract ReadableName as
                  name: string;
                end
                define class Fixed implements static ReadableName as
                  pub static const name: string = "fixed";
                end
                define class Mutable implements static ReadableName as
                  pub static let name: string = "mutable";
                end
                """)));

        IllegalArgumentException exactKind = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define static contract MutableName as
                          let name: string;
                        end
                        define class Bad implements static MutableName as
                          pub static const name: string = "fixed";
                        end
                        """)));
        assertTrue(exactKind.getMessage().contains("binding kind"));
    }

    @Test
    void writeCapableContractFieldIsTypeInvariant() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                        define static contract MutableSlot as
                          let value: any;
                        end
                        define class Narrow implements static MutableSlot as
                          pub static let value: int = 1;
                        end
                        """)));
        assertTrue(failure.getMessage().contains("type-invariant")
                || failure.getMessage().contains("does not implement static contract"));
    }

    @Test
    void staticFieldsAreNotConstructorSlots() throws Exception {
        String output = run("""
                define static contract CounterShape as
                  let count: int;
                  fnc bump(): int;
                end

                define class Counter implements static CounterShape as
                  pub static let count: int = 5;
                  val int value;

                  pub fnc get(): int { return self.value; }

                  pub static fnc bump(): int {
                    Counter.count = Counter.count + 1;
                    return Counter.count;
                  }
                end

                pub routine main(): void {
                  val counter = new Counter(42);
                  stdio.stdout.write(counter.get());
                  stdio.stdout.write("|");
                  stdio.stdout.write(Counter.count);
                  stdio.stdout.write("|");
                  stdio.stdout.write(Counter.bump());
                  stdio.stdout.write("|");
                  stdio.stdout.write(Counter.count);
                  return;
                }
                """);
        assertEquals("42|5|6|6", output);
    }

    @Test
    void staticInitializationMayReadEarlierButNotForwardField() throws Exception {
        assertEquals("42", run("""
                define class Config as
                  private static const base: int = 40;
                  private static const answer: int = Config.base + 2;
                  pub static fnc value(): int { return Config.answer; }
                end
                pub routine main(): void {
                  stdio.stdout.write(Config.value());
                  return;
                }
                """));

        assertThrows(Exception.class, () -> run("""
                define class Bad as
                  private static const first: int = Bad.second + 1;
                  private static const second: int = 41;
                  pub static fnc value(): int { return Bad.first; }
                end
                pub routine main(): void {
                  stdio.stdout.write(Bad.value());
                  return;
                }
                """));
    }

    @Test
    void immutableStaticFieldsRejectAssignment() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Config as
                          pub static const name: string = "fixed";
                        end
                        routine mutate(): void {
                          Config.name = "changed";
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("immutable"));
    }

    @Test
    void instanceAndStaticFieldNamespacesAreIndependent() throws Exception {
        assertEquals("7|class", run("""
                define class Namespaces as
                  val int value;
                  pub static const value: string = "class";
                  pub fnc instanceValue(): int { return self.value; }
                end
                pub routine main(): void {
                  val item = new Namespaces(7);
                  stdio.stdout.write(item.instanceValue());
                  stdio.stdout.write("|");
                  stdio.stdout.write(Namespaces.value);
                  return;
                }
                """));
    }

    @Test
    void inheritedStaticMethodsMaySatisfyContractButStaticStorageIsLocal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define static contract Factory as
                  fnc make(): int;
                end
                define class Base as
                  pub static fnc make(): int { return 1; }
                end
                define class Child extends Base implements static Factory as
                end
                """)));

        IllegalArgumentException fieldNotInherited = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define static contract Named as
                          name: string;
                        end
                        define class Base as
                          pub static const name: string = "base";
                        end
                        define class Child extends Base implements static Named as
                        end
                        """)));
        assertTrue(fieldNotInherited.getMessage().contains("static contract")
                || fieldNotInherited.getMessage().contains("static field"));
    }

    @Test
    void actorsCannotOwnStaticClassStateOrContracts() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define shared actor Bad as
                          static const count: int = 0;
                        end
                        """)));

        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define static contract Api as
                          fnc ping(): int;
                        end
                        define shared actor Bad implements static Api as
                          pub static fnc ping(): int { return 1; }
                        end
                        """)));
    }

    @Test
    void staticStorageRejectsBorrowAndMoveOnlyState() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub static const item: Box = new Box();
                        end
                        """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "static-class-contract-hardening.ores")
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
}

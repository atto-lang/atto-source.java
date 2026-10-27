package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExplicitExportLinkedRuntimeTest {
    @TempDir Path temp;

    @Test
    void aliasExportedDefaultPrivateClassIsLinkedAndConstructible() throws Exception {
        Path lib = temp.resolve("box.ores");
        Path main = temp.resolve("main.ores");

        Files.writeString(lib, """
                export class InternalBox as Box;

                define class InternalBox as
                  val int value;

                  pub constructor(int value) {
                    self.value = value;
                  }

                  pub get(): int {
                    return self.value;
                  }
                end
                """);

        Files.writeString(main, """
                import class Box from "./box.ores";

                pub fnc main(): void {
                  val Box box = new Box(41);
                  stdio.stdout.write(box.get());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("41", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aliasOnlyClassExportDoesNotLeakLocalSpelling() throws Exception {
        Path lib = temp.resolve("box-private-name.ores");
        Path main = temp.resolve("main-private-name.ores");

        Files.writeString(lib, """
                export class InternalBox as Box;

                define class InternalBox as
                  pub constructor() {
                    return;
                  }
                end
                """);

        Files.writeString(main, """
                import class InternalBox from "./box-private-name.ores";

                pub fnc main(): void {
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(
                failure.getMessage().contains("does not match an exported declaration"),
                failure.getMessage());
    }

    @Test
    void aliasedFunctionResolvesThroughBindingAtRuntime() throws Exception {
        Path lib = temp.resolve("fn.ores");
        Path main = temp.resolve("fn-main.ores");

        Files.writeString(lib, """
                export fnc hidden_add_one as add_one;

                fnc hidden_add_one(int value): int {
                  return value + 1;
                }
                """);

        Files.writeString(main, """
                import fnc add_one from "./fn.ores";

                pub fnc main(): void {
                  stdio.stdout.write(add_one(41));
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void wildcardNamespaceUsesExternalAliasNotLocalSpelling() throws Exception {
        Path lib = temp.resolve("wild.ores");
        Path main = temp.resolve("wild-main.ores");

        Files.writeString(lib, """
                export fnc hidden_value as value;

                fnc hidden_value(): int {
                  return 7;
                }
                """);

        Files.writeString(main, """
                import * as lib from "./wild.ores";

                pub fnc main(): void {
                  stdio.stdout.write(lib.value());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("7", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void exportedModuleMemberAliasResolvesToLocalCallable() throws Exception {
        Path lib = temp.resolve("service.ores");
        Path main = temp.resolve("service-main.ores");

        Files.writeString(lib, """
                export module Service;

                define module Service as
                  export fnc hidden_value as value;

                  fnc hidden_value(): int {
                    return 9;
                  }
                end
                """);

        Files.writeString(main, """
                import module Service from "./service.ores";

                pub fnc main(): void {
                  stdio.stdout.write(Service.value());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("9", out.toString(StandardCharsets.UTF_8));
    }


    @Test
    void exportedModuleRoutineAliasRemainsDirectCallable() throws Exception {
        Path lib = temp.resolve("service-routine.ores");
        Path main = temp.resolve("service-routine-main.ores");

        Files.writeString(lib, """
                export module Service;

                define module Service as
                  export routine hidden_value as value;

                  routine hidden_value(): int {
                    return 11;
                  }
                end
                """);

        Files.writeString(main, """
                import module Service from "./service-routine.ores";

                pub fnc main(): void {
                  stdio.stdout.write(Service.value());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("11", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void exportedClassDoesNotPublishPrivateConstructor() throws Exception {
        Path lib = temp.resolve("private-constructor.ores");
        Path main = temp.resolve("private-constructor-main.ores");

        Files.writeString(lib, """
                export class InternalBox as Box;

                define class InternalBox as
                  constructor() {
                    return;
                  }
                end
                """);

        Files.writeString(main, """
                import class Box from "./private-constructor.ores";

                pub fnc main(): void {
                  val Box box = new Box();
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(
                failure.getMessage().toLowerCase().contains("constructor"),
                failure.getMessage());
    }

    @Test
    void exportAliasMayBeRenamedAgainByImporter() throws Exception {
        Path lib = temp.resolve("import-alias.ores");
        Path main = temp.resolve("import-alias-main.ores");

        Files.writeString(lib, """
                export class InternalBox as PublicBox;

                define class InternalBox as
                  pub constructor() {
                    return;
                  }
                end
                """);

        Files.writeString(main, """
                import class PublicBox as LocalBox from "./import-alias.ores";

                pub fnc main(): void {
                  val LocalBox box = new LocalBox();
                  return;
                }
                """);

        LinkedProgramRunner.validate(main);
    }



    @Test
    void linkedClassAliasRewritesItsOwnPublicSignatureIdentity() throws Exception {
        Path lib = temp.resolve("self-type.ores");
        Path main = temp.resolve("self-type-main.ores");

        Files.writeString(lib, """
                export class InternalBox as Box;

                define class InternalBox as
                  pub constructor() {
                    return;
                  }

                  pub identity(): InternalBox {
                    return self;
                  }
                end
                """);

        Files.writeString(main, """
                import class Box from "./self-type.ores";

                pub fnc main(): void {
                  val Box first = new Box();
                  val Box second = first.identity();
                  return;
                }
                """);

        LinkedProgramRunner.validate(main);
    }


    @Test
    void inlinePubAndExplicitAliasExposeBothNames() throws Exception {
        Path lib = temp.resolve("both.ores");
        Path main = temp.resolve("both-main.ores");

        Files.writeString(lib, """
                export fnc visible as alias;

                pub fnc visible(): int {
                  return 5;
                }
                """);

        Files.writeString(main, """
                import fnc visible from "./both.ores";
                import fnc alias from "./both.ores";

                pub fnc main(): void {
                  stdio.stdout.write(visible());
                  stdio.stdout.write(alias());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("55", out.toString(StandardCharsets.UTF_8));
    }
}

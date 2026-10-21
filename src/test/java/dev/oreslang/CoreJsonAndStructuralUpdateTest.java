package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CoreJson;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreJsonAndStructuralUpdateTest {
    @Test
    void mapIndexedPutAddsDynamicKeysWithStaticKeyAndValueTypes() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Map<String, int> values = new Map<String, int>();
                  values["first"] = 11;
                  values["second"] = 22;
                  stdio.stdout.write(values["first"]);
                  stdio.stdout.write(values["second"]);
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        assertEquals("1122", run(program, "map-put.ores"));
    }

    @Test
    void mapIndexedPutRejectsWrongKeyAndValueTypes() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val Map<String, int> values = new Map<String, int>();
                          values[1] = 2;
                          return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val Map<String, int> values = new Map<String, int>();
                          values["x"] = "wrong";
                          return;
                        }
                        """)));
    }

    @Test
    void withProducesAClosedExtendedStructWithoutMutatingTheSource() throws Exception {
        String program = """
                pub fnc main(): void {
                  const original = infer struct{x: 3};
                  const extended = original with { y: 4 };
                  stdio.stdout.write(extended.x);
                  stdio.stdout.write(extended.y);
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        assertEquals("34", run(program, "struct-with.ores"));
    }

    @Test
    void withCannotBeUsedAsAnAliasingBackdoorForMoveOnlyStructs() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad(): int {
                          const original = infer struct{x: 3};
                          const extended = original with { y: 4 };
                          return original.x + extended.y;
                        }
                        """)));
    }

    @Test
    void jsonDecodeAndEncodeUseCheckedClassMetadataWithoutReflection() throws Exception {
        String program = """
                define class User as
                  @FromJson("id")
                  id: int;
                  @FromJson("display_name")
                  name: String;
                end

                pub fnc main(): void {
                  const User user =
                    Json.decode<User>("{\"id\":7,\"display_name\":\"Ada\"}");
                  stdio.stdout.write(user.getId());
                  stdio.stdout.write(user.getName());
                  stdio.stdout.write(Json.encode(user));
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        String output = run(program, "typed-json.ores");
        assertTrue(output.startsWith("7Ada"));
        assertTrue(output.endsWith("{\"id\":7,\"display_name\":\"Ada\"}"));
    }

    @Test
    void jsonDecodeSupportsTypedDynamicObjectMaps() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Map<String, int> values =
                    Json.decode<Map<String, int>>("{\"left\":10,\"right\":20}");
                  values["extra"] = 30;
                  stdio.stdout.write(values["right"]);
                  stdio.stdout.write(Json.encode(values));
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        assertEquals(
                "20{\"left\":10,\"right\":20,\"extra\":30}",
                run(program, "json-map.ores"));
    }

    @Test
    void jsonIsStrictAboutDuplicateUnknownAndMissingKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                CoreJson.parse("{\"x\":1,\"x\":2}"));

        String unknown = """
                define class User as
                  @FromJson("id")
                  id: int;
                end
                pub fnc main(): void {
                  const User user = Json.decode<User>("{\"id\":1,\"extra\":2}");
                  return;
                }
                """;
        assertThrows(Exception.class, () -> runChecked(unknown, "json-unknown.ores"));

        String missing = """
                define class User as
                  @FromJson("id")
                  id: int;
                  @FromJson("name")
                  name: String;
                end
                pub fnc main(): void {
                  const User user = Json.decode<User>("{\"id\":1}");
                  return;
                }
                """;
        assertThrows(Exception.class, () -> runChecked(missing, "json-missing.ores"));
    }

    @Test
    void jsonClassDecodeFailsClosedForConstructorsInheritanceAndPartialAnnotations() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class User as
                          @FromJson("id")
                          id: int;
                          name: String;
                        end
                        fnc bad(): User {
                          return Json.decode<User>("{\"id\":1,\"name\":\"x\"}");
                        }
                        """)));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class User as
                          @FromJson("id")
                          id: int;
                          constructor(int id) { self.id = id; }
                        end
                        fnc bad(): User {
                          return Json.decode<User>("{\"id\":1}");
                        }
                        """)));
    }

    @Test
    void fromJsonCannotTargetStaticState() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bad as
                          @FromJson("global")
                          static global: String = "x";
                        end
                        """)));
    }

    private static String runChecked(String program, String name) throws Exception {
        TypeChecker.check(Parser.parse(program));
        return run(program, name);
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
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

package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TypedOpenMapKeySchemaLanguageTest {
    private static final String SCHEMA = """
            define class Settings extends Map<string, unknown> as
              key name: Option<string>;
              key enabled: Option<bool>;
              key pending: Option<Option<bool>>;
            end
            """;

    @Test
    void parserKeepsKeySchemaOutOfPhysicalFieldsAndMethods() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse(SCHEMA)
                .modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .findFirst().orElseThrow();

        assertTrue(klass.fields().isEmpty());
        assertTrue(klass.methods().isEmpty());
        assertEquals(3, klass.mapKeys().size());
        assertEquals("pending", klass.mapKeys().get(2).name());
        assertEquals("Option", klass.mapKeys().get(2).declaredType().name());
        assertEquals("Option",
                klass.mapKeys().get(2).declaredType().arguments().getFirst().name());
    }

    @Test
    void compilerTransformsPreserveKeySchemaMetadata() {
        Ast.Program expanded = AnnotationExpander.expand(Parser.parse(SCHEMA));
        Ast.ClassDecl expandedClass = (Ast.ClassDecl) expanded.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .findFirst().orElseThrow();
        assertEquals(3, expandedClass.mapKeys().size());

        var shaken = OresCompiler.compileForBuild(SCHEMA + """
                pub fnc main(): void {
                  val m = new Settings();
                  m["name"] = "kept";
                  return;
                }
                """, BuildOptions.executable(Map.of()));
        Ast.ClassDecl shakenClass = (Ast.ClassDecl) shaken.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("Settings"))
                .findFirst().orElseThrow();
        assertEquals(3, shakenClass.mapKeys().size());
        assertEquals("pending", shakenClass.mapKeys().get(2).name());
    }

    @Test
    void typechecksKnownKeysOpenLiteralKeysAndNestedOptionValues() {
        TypeChecker.check(Parser.parse(SCHEMA + """
                fnc valid(): void {
                  val m = new Settings();
                  m["name"] = "hello";
                  m["enabled"] = false;
                  m["pending"] = Some(false);
                  m["extra"] = 7;

                  val string name = m["name"];
                  val bool enabled = m["enabled"];
                  val Option<bool> pending = m["pending"];
                  val unknown extra = m["extra"];
                  return;
                }
                """));
    }

    @Test
    void lookupOperationsPreserveOuterPresenceAndStoredOptionType() {
        TypeChecker.check(Parser.parse(SCHEMA + """
                fnc valid(): void {
                  val m = new Settings();
                  val bool missing = m.has("pending");
                  val Option<Option<bool>> first = m.get("pending");
                  m["pending"] = None;
                  val Option<Option<bool>> storedNone = m.get("pending");
                  val Option<Option<bool>> transferred = m.take("pending");
                  val bool removed = m.remove("pending");
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(SCHEMA + """
                        fnc invalid(): void {
                          val m = new Settings();
                          val Option<bool> wrong = m.get("name");
                          return;
                        }
                        """)));
    }

    @Test
    void lookupOperationsAlsoWorkForClosedGenericMap() {
        TypeChecker.check(Parser.parse("""
                fnc valid(): void {
                  val m = new Map<string, int>();
                  m["answer"] = 42;
                  val bool present = m.has("answer");
                  val Option<int> copy = m.get("answer");
                  val Option<int> moved = m.take("answer");
                  val bool removed = m.remove("answer");
                  return;
                }
                """));
    }

    @Test
    void consumingLookupRequiresMutableMapAuthority() {
        for (String operation : new String[] {"take", "remove"}) {
            String source = SCHEMA + """
                    fnc invalid(): void {
                      let m = new Settings();
                      m.%s("name");
                      return;
                    }
                    """.formatted(operation);
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(denied.getMessage().contains("requires"),
                    denied.getMessage());
        }
    }

    @Test
    void knownLiteralKeysRejectWrongStoredValues() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(SCHEMA + """
                        fnc invalid(): void {
                          val m = new Settings();
                          m["name"] = 7;
                          return;
                        }
                        """)));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(SCHEMA + """
                        fnc invalid(): void {
                          val m = new Settings();
                          m["pending"] = false;
                          return;
                        }
                        """)));
    }

    @Test
    void dynamicWritesFailClosedButDynamicReadsStayOpen() {
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class, () ->
                        TypeChecker.check(Parser.parse(SCHEMA + """
                                fnc invalid(): void {
                                  val m = new Settings();
                                  val string k = "name";
                                  m[k] = "hello";
                                  return;
                                }
                                """)));
        assertTrue(denied.getMessage().contains("dynamic writes"), denied.getMessage());

        TypeChecker.check(Parser.parse(SCHEMA + """
                fnc valid(): void {
                  val m = new Settings();
                  m["extra"] = 7;
                  val string k = "extra";
                  val unknown value = m[k];
                  return;
                }
                """));
    }

    @Test
    void schemaRequiresOuterOptionAndRejectsDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bad extends Map<string, unknown> as
                          key name: string;
                        end
                        """)));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bad extends Map<string, unknown> as
                          key name: Option<string>;
                          key name: Option<bool>;
                        end
                        """)));
    }

    @Test
    void keyDeclarationsAreNotVisibilityControlledMembers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad extends Map<string, unknown> as
                  pub key token: Option<string>;
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad extends Map<string, unknown> as
                  private key token: Option<string>;
                end
                """));
    }

    @Test
    void schemaClassRejectsFieldsConstructorsMethodsAndHooksInFirstSlice() {
        for (String member : new String[] {
                "val field: string;",
                "constructor() { return; }",
                "method helper(): void { return; }",
                "method on_get(string key): void { return; }"
        }) {
            String source = """
                    define class Bad extends Map<string, unknown> as
                      key name: Option<string>;
                      %s
                    end
                    """.formatted(member);
            assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse(source)), member);
        }
    }

    @Test
    void hashMapSpellingFailsWithPreciseDeferredRuntimeDiagnostic() {
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class, () ->
                        TypeChecker.check(Parser.parse("""
                                define class FutureMap extends HashMap<string, unknown> as
                                  key name: Option<string>;
                                end
                                """)));
        assertTrue(denied.getMessage().contains("HashMap"), denied.getMessage());
    }

    @Test
    void unreifiedSchemaNominalTestsAndOpenTopTestsFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(SCHEMA + """
                        fnc invalid(): bool {
                          val m = new Settings();
                          return m is Settings;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(SCHEMA + """
                        fnc invalid(): Settings {
                          val m = new Settings();
                          return m as Settings;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc invalid(unknown value): bool {
                          return value is unknown;
                        }
                        """)));
    }

    @Test
    void unknownIsReservedAgainstTypeNamespaceShadowing() {
        String[] invalid = {
                "define class unknown as end",
                "define interface unknown as end",
                "type unknown = int;",
                "fnc bad<unknown>(unknown value): unknown { return value; }",
                "define module unknown end"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    source);
        }
    }

    @Test
    void runtimeUsesHardenedKeyedStorageForSchemaInstances() throws Exception {
        String source = SCHEMA + """
                pub fnc main(): void {
                  val m = new Settings();
                  m["name"] = "hello";
                  m["enabled"] = false;
                  m["pending"] = Some(false);
                  m["extra"] = 7;
                  stdio.stdout.write(m["name"]);
                  stdio.stdout.write(m["enabled"]);
                  stdio.stdout.write(m["pending"].unwrap());
                  stdio.stdout.write(m["extra"] as int);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source src = Source.newBuilder(OresLanguage.ID, source, "typed-open-map-key-schema.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(src);
        }
        assertEquals("hellofalsefalse7",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runtimeLookupDistinguishesMissingStoredNoneAndStoredSome() throws Exception {
        String source = SCHEMA + """
                pub fnc main(): void {
                  val m = new Settings();
                  stdio.stdout.write(m.get("pending").is_none());
                  m["pending"] = None;
                  stdio.stdout.write(m.get("pending").is_some());
                  stdio.stdout.write(m.get("pending").unwrap().is_none());
                  m["pending"] = Some(false);
                  val moved = m.take("pending");
                  stdio.stdout.write(moved.is_some());
                  stdio.stdout.write(moved.unwrap().unwrap());
                  stdio.stdout.write(m.has("pending"));
                  m["extra"] = 7;
                  stdio.stdout.write(m.remove("extra"));
                  stdio.stdout.write(m.remove("extra"));
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source src = Source.newBuilder(
                OresLanguage.ID, source, "typed-open-map-option-lookup.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(src);
        }
        assertEquals("truetruetruetruefalsefalsetruefalse",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void requiredBracketReadStillRejectsMissingKeyAtRuntime() throws Exception {
        String source = SCHEMA + """
                pub fnc main(): void {
                  val m = new Settings();
                  m["name"];
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(source));
        Source src = Source.newBuilder(OresLanguage.ID, source, "typed-open-map-missing.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class, () -> context.eval(src));
            assertTrue(failure.getMessage().contains("unknown typed Map key"),
                    failure.getMessage());
        }
    }
}

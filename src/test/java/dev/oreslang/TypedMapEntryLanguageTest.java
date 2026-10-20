package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TypedMapEntryLanguageTest {
    private static Ast.Program checked(String source) {
        Ast.Program typed = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(typed);
        return typed;
    }

    private static String execute(String source) throws Exception {
        checked(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(
                        OresLanguage.ID, source, "typed-map-entry.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private static IllegalArgumentException rejected(String source) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void fromEntriesExecutesLookupPutTakeRemoveAndSize() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                  val map = Map.from_entries([
                    Entry.of("a", 1),
                    Entry.of("b", 2)
                  ]);

                  const previous = map.put("a", 5);
                  stdio.println(previous.unwrap() + map["a"] + map.size());

                  const found = map.get("b");
                  stdio.println(found.unwrap());

                  const taken = map.take("b");
                  stdio.println(taken.unwrap());
                  stdio.println(map.has("b"));
                  return;
                }
                """);

        assertEquals("8\n2\n2\nfalse\n", output);
    }

    @Test
    void duplicateEntriesAreRejectedAtomicallyAtGuestRuntime() throws Exception {
        String source = """
                pub fnc main(): void {
                  val map = Map.from_entries([
                    Entry.of("same", 1),
                    Entry.of("same", 2)
                  ]);
                  stdio.println(map.size());
                  return;
                }
                """;
        checked(source);

        Source guest = Source.newBuilder(
                        OresLanguage.ID, source, "typed-map-duplicate.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure =
                    assertThrows(PolyglotException.class, () -> context.eval(guest));
            assertTrue(
                    failure.getMessage().contains("duplicate logical key"),
                    failure.getMessage());
        }
    }

    @Test
    void inferredStringSymbolDomainRemainsUnionAtRuntime() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                  val map = Map.from_entries([
                    Entry.of("ready", 10),
                    Entry.of(#ready, 20)
                  ]);
                  map.put(#other, 12);
                  stdio.println(map["ready"] + map[#ready] + map[#other]);
                  return;
                }
                """);

        assertEquals("42\n", output);
    }

    @Test
    void explicitFactoryTypesPermitEmptyEntrySequence() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                  val map = Map.from_entries<String, int>([]);
                  map.put("answer", 42);
                  stdio.println(map["answer"]);
                  return;
                }
                """);

        assertEquals("42\n", output);
    }

    @Test
    void emptyInferenceAndUnsupportedDomainsFailClosed() {
        assertTrue(rejected("""
                fnc bad(): void {
                  val map = Map.from_entries([]);
                  return;
                }
                """).getMessage().contains("cannot infer Map"));

        String[] invalid = {
                "fnc bad(): void { val m = new Map<int, int>(); return; }",
                "fnc bad(): void { val m = new Map<String, float>(); return; }",
                "fnc bad(): void { val m = new Map<String, Array<int>>(); return; }",
                "fnc bad(): void { val m = new Map<String | int, int>(); return; }"
        };
        for (String source : invalid) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    source);
        }
    }

    @Test
    void indexedWritesAndReadonlyMutationRemainRejected() {
        IllegalArgumentException indexed = rejected("""
                fnc bad(): void {
                  val map = new Map<String, int>();
                  map["x"] = 1;
                  return;
                }
                """);
        assertTrue(indexed.getMessage().contains("Map.put"), indexed.getMessage());

        IllegalArgumentException readonly = rejected("""
                fnc bad(): void {
                  const map = new Map<String, int>();
                  map.put("x", 1);
                  return;
                }
                """);
        assertTrue(
                readonly.getMessage().contains("requires let mut or val"),
                readonly.getMessage());
    }

    @Test
    void factoryAndNamespaceAreSealed() {
        assertTrue(rejected("""
                fnc bad(): void {
                  const factory = Map.from_entries;
                  return;
                }
                """).getMessage().contains("direct-call-only"));

        assertTrue(rejected("""
                define class Map as
                  let int value = 1;
                end
                """).getMessage().contains("cannot be shadowed"));
    }

    @Test
    void mapCannotCrossActorCallableBoundary() {
        IllegalArgumentException failure = rejected("""
                pub actor fnc bad(Map<String, int> value): void {
                  return;
                }
                """);
        assertTrue(
                failure.getMessage().contains("Map")
                        && failure.getMessage().contains("actor boundary"),
                failure.getMessage());
    }

    @Test
    void callableReturnBoundaryPreservesExactMapShape() throws Exception {
        String output = execute("""
                fnc build(): Map<String, int> {
                  return Map.from_entries([
                    Entry.of("answer", 42)
                  ]);
                }

                pub fnc main(): void {
                  val map = build();
                  stdio.println(map["answer"]);
                  return;
                }
                """);
        assertEquals("42\n", output);
    }

    @Test
    void ownershipPassIndependentlyRejectsReadonlyMapMutation() {
        Ast.Program raw = Parser.parse("""
                fnc bad(): void {
                  const map = new Map<String, int>();
                  map.put("x", 1);
                  return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(raw));
        assertTrue(
                failure.getMessage().contains("read-only")
                        || failure.getMessage().contains("cannot mutate"),
                failure.getMessage());
    }
    @Test
    void directMapOwnershipMovesButNestedContainmentIsRejected() {
        String useAfterMove = """
                fnc consume(Map<String, int> map): int {
                  return map.size();
                }

                fnc bad(): int {
                  val map = Map.from_entries<String, int>([]);
                  const size = consume(map);
                  return size + map.size();
                }
                """;
        Ast.Program typed = TypeChecker.check(Parser.parse(useAfterMove));
        IllegalArgumentException moved = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(typed));
        assertTrue(
                moved.getMessage().contains("moved")
                        || moved.getMessage().contains("use after"),
                moved.getMessage());

        String[] nested = {
                "fnc bad(): void { val m = new Map<String, int>(); const xs = [m]; return; }",
                "fnc bad(): void { val m = new Map<String, int>(); const pair = (m, 1); return; }",
                "fnc bad(): void { val m = new Map<String, int>(); const e = Entry.of(\"m\", m); return; }",
                "fnc bad(): void { val m = new Map<String, int>(); const s = Some(m); return; }",
                "fnc bad(): void { val m = new Map<String, int>(); const r = Ok(m); return; }",
                "fnc bad(): void { val m = new Map<String, int>(); const x = record{map: m}; return; }",
                "fnc bad(): void { val m = new Map<String, int>(); const x = rt copy m; return; }",
                "fnc bad(): void { val m = new Map<String, int>(); const x = rt share m; return; }",
                "fnc bad(): void { val Channel<Map<String, int>> ch = Channel.new<Map<String, int>>(1); return; }"
        };
        for (String source : nested) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    source);
        }
    }

    @Test
    void mapCannotLiveInClassOrGlobalStorage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Holder as
                          val Map<String, int> values;
                        end
                        """)));

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        val Map<String, int> values = new Map<String, int>();
                        """)));
    }

    @Test
    void ownershipCheckerAlsoRejectsRawNestedMapLiteral() {
        Ast.Program raw = Parser.parse("""
                fnc bad(): void {
                  val map = new Map<String, int>();
                  const xs = [map];
                  return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(raw));
        assertTrue(failure.getMessage().contains("Map"), failure.getMessage());
    }


    @Test
    void fromEntriesSnapshotsWithoutConsumingSourceSequence() throws Exception {
        String output = execute("""
                pub fnc main(): void {
                  val entries = [
                    Entry.of("answer", 42)
                  ];
                  val map = Map.from_entries(entries);
                  const first = entries[0];
                  stdio.println(first.value + map["answer"]);
                  return;
                }
                """);
        assertEquals("84\n", output);
    }

    @Test
    void guestFromEntriesRejectsCanonicalUnicodeDuplicates() throws Exception {
        String source = """
                pub fnc main(): void {
                  val entries = [
                    Entry.of("caf\u00e9", 1),
                    Entry.of("cafe\u0301", 2)
                  ];
                  val map = Map.from_entries(entries);
                  stdio.println(map.size());
                  return;
                }
                """;
        checked(source);

        Source guest = Source.newBuilder(
                        OresLanguage.ID, source, "typed-map-nfc-duplicate.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            PolyglotException failure =
                    assertThrows(PolyglotException.class, () -> context.eval(guest));
            assertTrue(
                    failure.getMessage().contains("duplicate logical key"),
                    failure.getMessage());
        }
    }


}

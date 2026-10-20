package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class MapFromEntriesLanguageTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static IllegalArgumentException rejected(String source) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void fromEntriesInfersNominalMapAndSupportsTypedReads() {
        accepted("""
                fnc main(): int {
                  const result = Map.from_entries([
                    Entry.of(1, 10),
                    Entry.of(3, 30)
                  ]);
                  const map = result.unwrap();
                  const Option<int> maybe = map.get(3);
                  if map.has(1) then
                    return map[1] + maybe.unwrap() + map.size;
                  else
                    return 0;
                  fi
                }
                """);
    }

    @Test
    void explicitResultContractMatchesInferredMapAndDuplicateType() {
        accepted("""
                fnc build(): Result<Map<int, int>, DuplicateKey<int>> {
                  return Map.from_entries([
                    Entry.of(1, 10),
                    Entry.of(2, 20)
                  ]);
                }
                """);
    }

    @Test
    void duplicateKeysRemainLegalUntilMapConstruction() {
        accepted("""
                fnc build(): Result<Map<int, int>, DuplicateKey<int>> {
                  const entries = [
                    Entry.of(1, 10),
                    Entry.of(1, 20)
                  ];
                  return Map.from_entries(entries);
                }
                """);
    }

    @Test
    void fromEntriesRejectsNonEntrySequencesEmptyInferenceAndUnsupportedKeys() {
        IllegalArgumentException arrays = rejected("""
                fnc bad(): void {
                  const result = Map.from_entries([[1, 2], [3, 4]]);
                  return;
                }
                """);
        assertTrue(arrays.getMessage().contains("Entry"), arrays.getMessage());

        IllegalArgumentException tuples = rejected("""
                fnc bad(): void {
                  const result = Map.from_entries([(1, 2), (3, 4)]);
                  return;
                }
                """);
        assertTrue(tuples.getMessage().contains("Entry"), tuples.getMessage());

        IllegalArgumentException empty = rejected("""
                fnc bad(): void {
                  const result = Map.from_entries([]);
                  return;
                }
                """);
        assertTrue(empty.getMessage().contains("Entry")
                || empty.getMessage().contains("infer"), empty.getMessage());

        IllegalArgumentException floatKey = rejected("""
                fnc bad(): void {
                  const result = Map.from_entries([
                    Entry.of(1.5, 10)
                  ]);
                  return;
                }
                """);
        assertTrue(floatKey.getMessage().contains("key"), floatKey.getMessage());
    }

    @Test
    void v1RejectsMoveOnlyAndMutableContainerValues() {
        IllegalArgumentException listValue = rejected("""
                fnc bad(): void {
                  const result = Map.from_entries([
                    Entry.of(1, [10, 20])
                  ]);
                  return;
                }
                """);
        assertTrue(listValue.getMessage().contains("copy-safe"), listValue.getMessage());

        IllegalArgumentException classValue = rejected("""
                define class Box as
                end

                fnc bad(): void {
                  const result = Map.from_entries([
                    Entry.of(1, new Box())
                  ]);
                  return;
                }
                """);
        assertTrue(classValue.getMessage().contains("copy-safe"), classValue.getMessage());
    }

    @Test
    void typedMapIsImmutableAtBothTypeAndOwnershipLayers() {
        IllegalArgumentException typeFailure = rejected("""
                fnc bad(): void {
                  let mut result = Map.from_entries([Entry.of(1, 10)]);
                  let mut map = result.unwrap();
                  map[1] = 20;
                  return;
                }
                """);
        assertTrue(typeFailure.getMessage().contains("immutable"), typeFailure.getMessage());

        Ast.Program parsed = Parser.parse("""
                fnc bad(Map<int, int> map): void {
                  map[1] = 20;
                  return;
                }
                """);
        IllegalArgumentException ownership = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed));
        assertTrue(ownership.getMessage().contains("Map"), ownership.getMessage());
    }

    @Test
    void mapNamespaceAndGenericTypeCannotBeShadowed() {
        IllegalArgumentException klass = rejected("""
                define class Map as
                end
                """);
        assertTrue(klass.getMessage().contains("cannot be shadowed"), klass.getMessage());

        IllegalArgumentException generic = rejected("""
                fnc bad<Map>(Map value): void {
                  return;
                }
                """);
        assertTrue(generic.getMessage().contains("cannot shadow"), generic.getMessage());
    }

    @Test
    void runtimeBuildsReadsAndIteratesIntegerMapInInsertionOrder() throws Exception {
        String code = """
                pub fnc main(): void {
                  const result = Map.from_entries([
                    Entry.of(3, 30),
                    Entry.of(1, 10),
                    Entry.of(2, 20)
                  ]);
                  const map = result.unwrap();

                  stdio.println(map[1]);
                  stdio.println(map.has(4));
                  stdio.println(map.get(2).unwrap());
                  stdio.println(map.size);

                  for let entry of map {
                    stdio.println(entry.key);
                    stdio.println(entry.value);
                  }
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "map-from-entries.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals(
                "10\nfalse\n20\n3\n3\n30\n1\n10\n2\n20",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

    @Test
    void runtimeDuplicateResultCarriesTheDuplicateKey() throws Exception {
        String code = """
                pub fnc main(): void {
                  const result = Map.from_entries([
                    Entry.of(7, 10),
                    Entry.of(7, 20)
                  ]);
                  match result over
                    on Ok(map) -> {
                      stdio.println("unexpected");
                    }
                    on Err(error) -> {
                      stdio.println(error.key);
                    }
                  end
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "map-duplicate.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals(
                "7",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

    @Test
    void stringAndSymbolMapsRemainNominallySeparateKeyDomains() {
        accepted("""
                fnc strings(): Result<Map<String, int>, DuplicateKey<String>> {
                  return Map.from_entries([
                    Entry.of("a", 1),
                    Entry.of("b", 2)
                  ]);
                }

                fnc symbols(): Result<Map<Symbol, int>, DuplicateKey<Symbol>> {
                  return Map.from_entries([
                    Entry.of(#ready, 1),
                    Entry.of(#error, 2)
                  ]);
                }
                """);

        IllegalArgumentException wrongLookup = rejected("""
                fnc bad(Map<String, int> map): int {
                  return map[#ready];
                }
                """);
        assertTrue(wrongLookup.getMessage().contains("Map key")
                || wrongLookup.getMessage().contains("expected"), wrongLookup.getMessage());
    }
}

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

final class EntryValueTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static IllegalArgumentException rejected(String source) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    private static void ownershipAccepted(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    private static IllegalArgumentException ownershipRejected(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        return assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
    }

    @Test
    void entryOfInfersReadonlyKeyValueProduct() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc key(Entry<int, string> entry): int {
                  return entry.key;
                }

                fnc main(): int {
                  return key(Entry.of(7, "seven"));
                }
                """));

        Ast.FunctionDecl main = program.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, main.body().getFirst());
        assertInstanceOf(Ast.CallExpr.class, returned.value());
    }

    @Test
    void arrayOfEntriesIsDistinctFromArrayOfArraysAndArrayOfTuples() {
        accepted("""
                fnc consume(Entry<int, int> entry): int {
                  return entry.key + entry.value;
                }

                fnc main(): int {
                  const entries = [
                    Entry.of(1, 2),
                    Entry.of(3, 4),
                    Entry.of(5, 6)
                  ];
                  return consume(entries[1]);
                }
                """);

        assertTrue(rejected("""
                fnc consume(Entry<int, int> entry): int {
                  return entry.key + entry.value;
                }

                fnc bad(): int {
                  const values = [[1, 2], [3, 4]];
                  return consume(values[0]);
                }
                """).getMessage().contains("argument"));

        assertTrue(rejected("""
                fnc consume(Entry<int, int> entry): int {
                  return entry.key + entry.value;
                }

                fnc bad(): int {
                  const pairs = [(1, 2), (3, 4)];
                  return consume(pairs[0]);
                }
                """).getMessage().contains("argument"));
    }

    @Test
    void duplicateKeysAreLegalInEntrySequences() {
        accepted("""
                fnc main(): int {
                  const entries = [
                    Entry.of(1, 10),
                    Entry.of(1, 20)
                  ];
                  return entries[0].key + entries[1].key;
                }
                """);
    }

    @Test
    void entriesIterateAsNamedProducts() {
        accepted("""
                fnc main(): int {
                  let total = 0;
                  const entries = [
                    Entry.of(1, 2),
                    Entry.of(3, 4),
                    Entry.of(5, 6)
                  ];
                  for let entry of entries {
                    total = total + entry.key + entry.value;
                  }
                  return total;
                }
                """);
    }

    @Test
    void entryFieldsRemainReadonlyAndFactoryArityIsExact() {
        IllegalArgumentException mutation = rejected("""
                fnc bad(): void {
                  val entry = Entry.of(1, 2);
                  entry.key = 3;
                  return;
                }
                """);
        assertTrue(mutation.getMessage().contains("readonly")
                || mutation.getMessage().contains("immutable"),
                mutation.getMessage());

        IllegalArgumentException arity = rejected("""
                fnc bad(): void {
                  const entry = Entry.of(1);
                  return;
                }
                """);
        assertTrue(arity.getMessage().contains("exactly two"), arity.getMessage());
    }

    @Test
    void entryOfExecutesAndForOfReadsKeyAndValue() throws Exception {
        String code = """
                pub fnc main(): void {
                  let total = 0;
                  const entries = [
                    Entry.of(1, 2),
                    Entry.of(3, 4),
                    Entry.of(5, 6)
                  ];
                  for let entry of entries {
                    total = total + entry.key + entry.value;
                  }
                  stdio.println(total);
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "entry-values.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(
                "21",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }
    @Test
    void builtinEntryNamespaceCannotBeShadowedAtModuleScope() {
        IllegalArgumentException klass = rejected("""
                define class Entry as
                end
                """);
        assertTrue(klass.getMessage().contains("cannot be shadowed"), klass.getMessage());

        IllegalArgumentException alias = rejected("""
                type Entry<K,V> = Tuple[K,V];
                fnc main(): void { return; }
                """);
        assertTrue(alias.getMessage().contains("cannot be shadowed"), alias.getMessage());

        IllegalArgumentException generic = rejected("""
                fnc bad<Entry>(Entry value): void {
                  return;
                }
                """);
        assertTrue(generic.getMessage().contains("cannot shadow"), generic.getMessage());
    }

    @Test
    void ownershipPreservesEntryShapeAndTupleCompatibilityType() {
        ownershipAccepted("""
                fnc consume(Entry<int, int> entry): int {
                  return entry.key + entry.value;
                }

                fnc main(): int {
                  const pair = tuple(10, 11);
                  const entry = Entry.of(...pair);
                  return consume(entry);
                }
                """);
    }

    @Test
    void entryOfRejectsBorrowedPayloadsInOwnershipPass() {
        IllegalArgumentException failure = ownershipRejected("""
                fnc bad(&int key): void {
                  const entry = Entry.of(key, 1);
                  return;
                }
                """);
        assertTrue(failure.getMessage().contains("borrowed"), failure.getMessage());
    }

    @Test
    void entryFactoryFailsClosedOutsideDirectOfCalls() {
        IllegalArgumentException unknown = rejected("""
                fnc bad(): void {
                  Entry.nope(1, 2);
                  return;
                }
                """);
        assertTrue(unknown.getMessage().contains("unknown Entry member"), unknown.getMessage());

        IllegalArgumentException extracted = rejected("""
                fnc bad(): void {
                  const factory = Entry.of;
                  return;
                }
                """);
        assertTrue(extracted.getMessage().contains("direct-call-only"), extracted.getMessage());
    }

    @Test
    void entryOfTupleSpreadExecutes() throws Exception {
        String code = """
                pub fnc main(): void {
                  const pair = tuple {7, 9};
                  const entry = Entry.of(...pair);
                  stdio.println(entry.key + entry.value);
                  return;
                }
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "entry-spread.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(
                "16",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

    @Test
    void ownershipNormalizesDeclaredEntryCallResults() {
        ownershipAccepted("""
                fnc buildEntry(): Entry<int, int> {
                  return Entry.of(20, 22);
                }

                fnc main(): int {
                  return buildEntry().key + buildEntry().value;
                }
                """);
    }

}

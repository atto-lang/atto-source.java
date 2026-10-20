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

    private static IllegalArgumentException ownershipRejectedDirect(String source) {
        Ast.Program program = Parser.parse(source);
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

    @Test
    void resumableCallsPreserveTupleFallbackAndSpreadExpansion() throws Exception {
        String code = """
                async fnc loadPair(): (int, int) {
                  return tuple {7, 9};
                }

                async fnc scalar(): int {
                  return 7;
                }

                async fnc total(): int {
                  const entry = Entry.of(...(await loadPair()));
                  const pair = tuple(await scalar(), entry.value);
                  return pair[0] + pair[1];
                }

                pub fnc main(): void {
                  stdio.println(await total());
                  return;
                }
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, code, "entry-resumable.ores")
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
    void structuralKeyValueShapesCannotImpersonateNominalEntry() {
        IllegalArgumentException structFailure = rejected("""
                fnc consume(Entry<int, int> entry): int {
                  return entry.key + entry.value;
                }

                fnc bad(): int {
                  const Fake fake = infer<Fake> struct {
                    key: 1,
                    value: 2
                  };
                  return consume(fake);
                }
                """);
        assertTrue(structFailure.getMessage().contains("argument"), structFailure.getMessage());

        IllegalArgumentException recordFailure = rejected("""
                fnc consume(Entry<int, int> entry): int {
                  return entry.key + entry.value;
                }

                fnc bad(): int {
                  const Fake fake = infer<Fake> record {
                    key: 1,
                    value: 2
                  };
                  return consume(fake);
                }
                """);
        assertTrue(recordFailure.getMessage().contains("argument"), recordFailure.getMessage());
    }

    @Test
    void entryComputedReadsRemainNominalAndReadonly() {
        accepted("""
                fnc main(): int {
                  const entry = Entry.of(20, 22);
                  const field = "key";
                  return entry[field] + entry["value"];
                }
                """);

        IllegalArgumentException failure = rejected("""
                fnc bad(): void {
                  let entry = Entry.of(1, 2);
                  entry["key"] = 9;
                  return;
                }
                """);
        assertTrue(failure.getMessage().contains("immutable"), failure.getMessage());
    }

    @Test
    void entryNestedInOptionAndResultRetainsNominalType() {
        accepted("""
                fnc use(Entry<int, int> entry): int {
                  return entry.key + entry.value;
                }

                fnc main(): int {
                  const Option<Entry<int, int>> maybe = Some(Entry.of(1, 2));
                  const Result<Entry<int, int>, string> result = Ok(Entry.of(3, 4));
                  return use(maybe.unwrap()) + use(result.unwrap());
                }
                """);
    }


    @Test
    void entryTypePatternIsReifiedFromRuntimeTagAndComponents() throws Exception {
        String code = """
                pub fnc main(): void {
                  const entry = Entry.of(4, 5);
                  if entry is type Entry<int, int> exact then
                    stdio.println(exact.key + exact.value);
                  else
                    stdio.println(0);
                  fi
                  return;
                }
                """;

        Ast.Program typed = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "entry-type-pattern.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(
                "9",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

    @Test
    void entryRuntimeEqualityDoesNotCollapseToStructuralRecordEquality() throws Exception {
        String code = """
                pub fnc main(): void {
                  const left = Entry.of(1, 2);
                  const right = Entry.of(1, 2);
                  const Fake fake = infer<Fake> record {
                    key: 1,
                    value: 2
                  };
                  stdio.println(left == right);
                  stdio.println(left == fake);
                  return;
                }
                """;

        Ast.Program typed = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "entry-equality.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(
                "true\nfalse",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }

    @Test
    void entryPatternRejectsUnreifiableNestedGenericPredicates() {
        IllegalArgumentException failure = rejected("""
                fnc bad(Entry<Option<int>, int> entry): int {
                  if entry is type Entry<Option<int>, int> exact then
                    return exact.value;
                  else
                    return 0;
                  fi
                }
                """);
        assertTrue(
                failure.getMessage().contains("reified")
                        || failure.getMessage().contains("parameterized"),
                failure.getMessage());
    }

    @Test
    void ownershipCheckerIndependentlyRejectsWritesThroughMutableEntryBinding() {
        IllegalArgumentException member = ownershipRejectedDirect("""
                fnc bad(): void {
                  let mut entry = Entry.of(1, 2);
                  entry.key = 3;
                  return;
                }
                """);
        assertTrue(member.getMessage().contains("Entry"), member.getMessage());

        IllegalArgumentException indexed = ownershipRejectedDirect("""
                fnc bad(): void {
                  let mut entry = Entry.of(1, 2);
                  entry["value"] = 3;
                  return;
                }
                """);
        assertTrue(indexed.getMessage().contains("Entry"), indexed.getMessage());
    }

    @Test
    void entryConstructionIsFactoryOnly() {
        IllegalArgumentException typed = rejected("""
                fnc bad(): Entry<int, int> {
                  return new Entry<int, int>();
                }
                """);
        assertTrue(typed.getMessage().contains("Entry.of"), typed.getMessage());

        IllegalArgumentException ownership = ownershipRejectedDirect("""
                fnc bad(): void {
                  const entry = new Entry<int, int>();
                  return;
                }
                """);
        assertTrue(ownership.getMessage().contains("Entry.of"), ownership.getMessage());
    }


    @Test
    void entryCallableReturnRetainsNominalRuntimeTag() throws Exception {
        String code = """
                fnc make(): Entry<int, int> {
                  return Entry.of(20, 22);
                }

                pub fnc main(): void {
                  const entry = make();
                  stdio.println(entry.key + entry.value);
                  return;
                }
                """;

        Ast.Program typed = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, code, "entry-return-shape.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(
                "42",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }


}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CheckedDynamicIndexAccessTest {

    private static String execute(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "checked-index-access.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void dynamicHomogeneousStructAndTupleReadsExecute() throws Exception {
        String source = """
                pub routine main(): void {
                  const point = infer struct{x: 3, y: 4};
                  const string key = "y";
                  stdio.stdout.write(point[key]);
                  stdio.stdout.write("|");
                  const values = (11, 13);
                  const int index = 0;
                  stdio.stdout.write(values[index]);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("4|11", execute(source));
    }

    @Test
    void narrowedRecordViewRejectsHiddenRuntimeKey() {
        String source = """
                fnc read({x: int} point, string key): int {
                  return point[key];
                }
                pub routine main(): void {
                  const point = struct{x: int, secret: string}{
                    x: 7, secret: "hidden"
                  };
                  stdio.stdout.write(read(point, "secret"));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertThrows(PolyglotException.class, () -> execute(source));
    }

    @Test
    void cachedNarrowedIndexSiteNeverAuthorizesHiddenKeyAfterValidRead() {
        String source = """
                fnc read({x: int} point, string key): int {
                  return point[key];
                }
                pub routine main(): void {
                  const point = struct{x: int, secret: string}{
                    x: 7, secret: "hidden"
                  };
                  stdio.stdout.write(read(point, "x"));
                  // Same checked IndexExpr now has a warmed evaluator plan.
                  stdio.stdout.write(read(point, "secret"));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertThrows(PolyglotException.class, () -> execute(source));
    }

    @Test
    void narrowedRecordViewAcceptsExposedRuntimeKey() throws Exception {
        String source = """
                fnc read({x: int} point, string key): int {
                  return point[key];
                }
                pub routine main(): void {
                  const point = struct{x: int, secret: string}{
                    x: 7, secret: "hidden"
                  };
                  stdio.stdout.write(read(point, "x"));
                  return;
                }
                """;
        assertEquals("7", execute(source));
    }

    @Test
    void dynamicClassIndexReadsPublicDataField() throws Exception {
        String source = """
                define class Candidate as
                  pub val int id = 9;
                  private val string secret = "hidden";
                  pub fnc score(): int { return 4; }
                end
                fnc get(string name): int {
                  const instance = new Candidate();
                  return instance[name];
                }
                pub routine main(): void {
                  stdio.stdout.write(get("id"));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("9", execute(source));
    }

    @Test
    void structuralCallableContractIsNotADataField() {
        String declarations = """
                define interface Readable
                  x: int;
                  fnc render(int value) => int;
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(declarations + """
                fnc read(structural Readable point, string key): int {
                  return point[key];
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(declarations + """
                fnc bad(structural Readable point): int {
                  return point["instance$render$arity1$generics0"];
                }
                """)));
    }

    @Test
    void privateMoveOnlyFieldDoesNotPoisonPublicCopyableIndexView() throws Exception {
        String source = """
                define class Candidate as
                  pub val int id = 42;
                  private val Array<int> resources = new Array<int>();
                end
                fnc read(string key): int {
                  const value = new Candidate();
                  return value[key];
                }
                pub routine main(): void {
                  stdio.stdout.write(read("id"));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("42", execute(source));
    }

    @Test
    void dynamicClassIndexRejectsPrivateFieldAndMethod() {
        String typeOnly = """
                define class Candidate as
                  pub val int id = 9;
                  private val string secret = "hidden";
                  pub fnc score(): int { return 4; }
                end
                fnc get(string name): int {
                  const instance = new Candidate();
                  return instance[name];
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(typeOnly)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                typeOnly + "fnc bad(): string { return (new Candidate())[\"secret\"]; }")));
        String runtime = typeOnly + """
                pub routine main(): void {
                  stdio.stdout.write(get("secret"));
                  return;
                }
                """;
        assertThrows(PolyglotException.class, () -> execute(runtime));
        String method = typeOnly + """
                pub routine main(): void {
                  stdio.stdout.write(get("score"));
                  return;
                }
                """;
        assertThrows(PolyglotException.class, () -> execute(method));
    }

    @Test
    void partiallyInitializedStructAllowsOnlyProvenInitializedLiteralKey() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc safe(): string {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const string result = point["foo"];
                  point.ready = true;
                  return result;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(string key): string {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const value = point[key];
                  point.ready = true;
                  return "unreachable";
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  return point["ready"];
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc safe(string key): string {
                  const mut point = struct{foo: string, ready: string}{foo: "before"};
                  point.ready = "after";
                  return point[key];
                }
                """)));
    }

    @Test
    void immutableKeyAliasCanReadOnlyAnInitializedPartialStructField() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc safe(): string {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const string key = "foo";
                  const string observed = point[key];
                  point.ready = true;
                  return observed;
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const string key = "ready";
                  return point[key];
                }
                """)));
    }

    @Test
    void closedRecordDoesNotDuplicateMoveOnlyFieldThroughLiteralIndex() {
        String source = """
                fnc bad(): Array<int> {
                  const record = struct{items: Array<int>}{items: new Array<int>()};
                  return record["items"];
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void dynamicClassIndexCannotExtractMoveOnlyField() {
        String source = """
                define class Store as
                  pub val Array<int> items = new Array<int>();
                end
                fnc bad(string key): Array<int> {
                  const store = new Store();
                  return store[key];
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void constructorCastReceiverDoesNotPermitIndexedSelfRead() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                define class Box as
                  val int value;
                  constructor(int next) {
                    const int observed = (self as Box)["value"];
                    self.value = next;
                  }
                end
                """)));
        assertTrue(failure.getMessage().contains("constructor self"));
    }

    @Test
    void constructorNestedFieldReceiverDoesNotPermitIndexedSelfRead() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse("""
                define class Box as
                  val Array<int> items;
                  constructor() {
                    const int first = self.items[0];
                    self.items = new Array<int>();
                  }
                end
                """)));
        assertTrue(failure.getMessage().contains("constructor self"));
    }

    @Test
    void constructorSelfCannotUseComputedFieldReadBeforeInitializationProof() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                define class Box as
                  val int value;
                  constructor(int next) {
                    const int observed = self["value"];
                    self.value = next;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                define class Box as
                  val int value;
                  constructor(int next) {
                    self.value = next;
                    const string field = "value";
                    const int observed = self[field];
                  }
                end
                """)));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                define class Box as
                  val int value;
                  constructor(int next) {
                    self.value = next;
                  }
                  pub get(): int {
                    const string field = "value";
                    return self[field];
                  }
                end
                """)));
    }

    @Test
    void fixedTupleReadCannotDuplicateMoveOnlyElement() {
        String source = """
                fnc bad(): Array<int> {
                  const values = (new Array<int>(), 3);
                  return values[0];
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void immutableStringKeyAliasesSelectExactHeterogeneousRecordAndClassFields()
            throws Exception {
        String program = """
                define class Mixed as
                  pub val int count = 1;
                  pub val string label = "class-value";
                end
                pub routine main(): void {
                  const record = infer struct{count: 3, label: "record-value"};
                  const string labelKey = "label";
                  const string recordResult = record[labelKey];
                  const keyAlias = labelKey;
                  const string aliasResult = record[keyAlias];
                  const item = new Mixed();
                  const string classResult = item[labelKey];
                  stdio.stdout.write(recordResult);
                  stdio.stdout.write("|");
                  stdio.stdout.write(aliasResult);
                  stdio.stdout.write("|");
                  stdio.stdout.write(classResult);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("record-value|record-value|class-value", execute(program));
    }

    @Test
    void provenTupleIndexAliasCanReadCopyFieldAlongsideAffineField()
            throws Exception {
        String program = """
                pub routine main(): void {
                  const values = (17, new Array<int>());
                  const int selected = 0;
                  const next = selected;
                  const int result = values[next];
                  stdio.stdout.write(result);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals("17", execute(program));
    }

    @Test
    void immutableSignedAndOutOfRangeTupleAliasesRejectAtTypeCheck() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                fnc bad(): int {
                  const values = (1, 2);
                  const int index = -1;
                  return values[index];
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                fnc bad(): int {
                  const values = (1, 2);
                  const int index = 12;
                  return values[index];
                }
                """)));
    }

    @Test
    void privateClassFieldConstantAliasIsRejectedBeforeRuntime() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                define class Vault as
                  pub val int value = 3;
                  private val int secret = 19;
                end
                fnc bad(): int {
                  const vault = new Vault();
                  const string key = "secret";
                  return vault[key];
                }
                """)));
    }

    @Test
    void mutableStringIndexCannotGainConstantKeyPrivileges() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(string runtimeKey): int {
                  let string chosen = "x";
                  chosen = runtimeKey;
                  const record = infer struct{x: 3, y: "different"};
                  return record[chosen];
                }
                """)));
    }

    @Test
    void constantUnknownFieldIsRejectedWithoutFallingBackToRuntimeLookup() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): int {
                  const record = infer struct{x: 3};
                  const string key = "secret";
                  return record[key];
                }
                """)));
    }

    @Test
    void indexedProofDoesNotTransferAcrossDifferentReceivers() {
        Ast.IndexExpr first = new Ast.IndexExpr(
                new Ast.NameExpr("first"), new Ast.NameExpr("key"));
        Ast.CheckedIndexKeys.record(first, Set.of("public"));
        Ast.IndexExpr second = new Ast.IndexExpr(
                new Ast.NameExpr("second"), new Ast.NameExpr("key"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(first, second));
        assertEquals(false, Ast.CheckedIndexKeys.hasProjectionProof(second));
        Ast.IndexExpr clone = new Ast.IndexExpr(
                first.receiver(), new Ast.NameExpr("key"));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.propagate(first, clone));
        assertEquals(Set.of("public"), Ast.CheckedIndexKeys.requirePlan(clone));
    }

    @Test
    void tupleProofDoesNotTransferAcrossDifferentReceivers() {
        Ast.IndexExpr first = new Ast.IndexExpr(
                new Ast.NameExpr("first"), new Ast.NameExpr("index"));
        Ast.CheckedIndexKeys.recordTupleSlot(first, 0);
        Ast.IndexExpr second = new Ast.IndexExpr(
                new Ast.NameExpr("second"), new Ast.NameExpr("index"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(first, second));
        assertEquals(null, Ast.CheckedIndexKeys.knownTupleSlot(second));
        Ast.IndexExpr clone = new Ast.IndexExpr(
                first.receiver(), new Ast.NameExpr("index"));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.propagate(first, clone));
        assertEquals(0, Ast.CheckedIndexKeys.knownTupleSlot(clone));
    }

    @Test
    void buildOptimizerRetainsCheckedReceiverWhenFoldingConditionals() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                pub const bool first = true;
                pub fnc select(string key): int {
                  return (first ? infer struct{x: 7} : infer struct{x: 9})[key];
                }
                pub routine main(): void {
                  stdio.stdout.write(select("x"));
                  return;
                }
                """, BuildOptions.executable(java.util.Map.of("first", "true")));
        Ast.FunctionDecl select = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("select"))
                .findFirst().orElseThrow();
        Ast.ReturnStmt returned = (Ast.ReturnStmt) select.body().getFirst();
        Ast.IndexExpr indexed = (Ast.IndexExpr) returned.value();
        assertTrue(indexed.receiver() instanceof Ast.ConditionalExpr,
                "checked receiver cannot be replaced by a folded alternate");
        assertEquals(Set.of("x"), Ast.CheckedIndexKeys.requirePlan(indexed));
    }

    @Test
    void buildOptimizerLeavesIndexedReturnHelperUninlined() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                fnc pick(int index): int {
                  return (7, 9)[index];
                }
                pub routine main(): void {
                  stdio.stdout.write(pick(0));
                  return;
                }
                """, BuildOptions.executable(java.util.Map.of()));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".pick"),
                "indexed returns are not eligible for unchecked constant-call inlining");
    }

    @Test
    void uncheckedRewriteCannotInheritProofFromPrecheckedDestination() {
        Ast.IndexExpr unchecked = new Ast.IndexExpr(
                new Ast.NameExpr("record"), new Ast.NameExpr("key"));
        Ast.IndexExpr targetField = new Ast.IndexExpr(
                new Ast.NameExpr("record"), new Ast.NameExpr("key"));
        Ast.CheckedIndexKeys.record(targetField, Set.of("id"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(unchecked, targetField));
        assertEquals(false, Ast.CheckedIndexKeys.hasProjectionProof(unchecked));
        assertEquals(Set.of("id"), Ast.CheckedIndexKeys.requirePlan(targetField));

        Ast.IndexExpr targetTuple = new Ast.IndexExpr(
                new Ast.NameExpr("values"), new Ast.NameExpr("position"));
        Ast.CheckedIndexKeys.recordTupleSlot(targetTuple, 0);
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(unchecked, targetTuple));
        assertEquals(0, Ast.CheckedIndexKeys.knownTupleSlot(targetTuple));

        Ast.IndexExpr fresh = new Ast.IndexExpr(
                new Ast.NameExpr("array"), new Ast.LiteralExpr(0L));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.propagate(unchecked, fresh));
        assertEquals(false, Ast.CheckedIndexKeys.hasProjectionProof(fresh));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.requireTupleSlot(fresh, 0));
    }

    @Test
    void optimizerRejectsLiteralOutsideCertifiedFieldViewWithoutInstallingProof() {
        Ast.IndexExpr checked = new Ast.IndexExpr(
                new Ast.NameExpr("record"), new Ast.NameExpr("key"));
        Ast.CheckedIndexKeys.record(checked, Set.of("public"));
        Ast.IndexExpr forbidden = new Ast.IndexExpr(
                checked.receiver(), new Ast.LiteralExpr("private"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(checked, forbidden));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.requirePlan(forbidden));
        Ast.IndexExpr wrongType = new Ast.IndexExpr(
                checked.receiver(), new Ast.LiteralExpr(0L));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(checked, wrongType));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.requirePlan(wrongType));

        Ast.IndexExpr allowed = new Ast.IndexExpr(
                checked.receiver(), new Ast.LiteralExpr("public"));
        assertEquals(allowed, Ast.CheckedIndexKeys.propagate(checked, allowed));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.require(allowed, "public"));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.require(allowed, "private"));
    }

    @Test
    void optimizerRejectsChangedCertifiedTupleSlotWithoutInstallingProof() {
        Ast.IndexExpr checked = new Ast.IndexExpr(
                new Ast.NameExpr("tuple"), new Ast.NameExpr("offset"));
        Ast.CheckedIndexKeys.recordTupleSlot(checked, 0);
        Ast.IndexExpr bad = new Ast.IndexExpr(
                checked.receiver(), new Ast.LiteralExpr(1L));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(checked, bad));
        assertEquals(null, Ast.CheckedIndexKeys.knownTupleSlot(bad));
        Ast.IndexExpr wrongType = new Ast.IndexExpr(
                checked.receiver(), new Ast.LiteralExpr("0"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(checked, wrongType));
        assertEquals(null, Ast.CheckedIndexKeys.knownTupleSlot(wrongType));

        Ast.IndexExpr good = new Ast.IndexExpr(
                checked.receiver(), new Ast.LiteralExpr(0L));
        assertEquals(good, Ast.CheckedIndexKeys.propagate(checked, good));
        assertEquals(0, Ast.CheckedIndexKeys.knownTupleSlot(good));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.requireTupleSlot(good, 0));
    }

    @Test
    void positionalProofIsBoundToExactAstIdentityAndPreservedOnClone() {
        Ast.IndexExpr first = new Ast.IndexExpr(
                new Ast.NameExpr("tuple"), new Ast.NameExpr("index"));
        Ast.IndexExpr second = new Ast.IndexExpr(
                first.receiver(), new Ast.NameExpr("index"));
        Ast.CheckedIndexKeys.recordTupleSlot(first, 0);
        assertEquals(0, Ast.CheckedIndexKeys.knownTupleSlot(first));
        assertEquals(null, Ast.CheckedIndexKeys.knownTupleSlot(second));
        Ast.CheckedIndexKeys.propagate(first, second);
        assertEquals(0, Ast.CheckedIndexKeys.knownTupleSlot(second));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.requireTupleSlot(second, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.requireTupleSlot(second, 1));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.recordTupleSlot(first, 1));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.record(first, Set.of("x")));
    }

    @Test
    void structurallyEqualButDistinctFieldReceiverIsNotAProof() {
        Ast.Expr originalReceiver = new Ast.MemberExpr(new Ast.NameExpr("context"), "record");
        Ast.Expr lookalikeReceiver = new Ast.MemberExpr(new Ast.NameExpr("context"), "record");
        assertEquals(originalReceiver, lookalikeReceiver);
        assertNotSame(originalReceiver, lookalikeReceiver);
        Ast.IndexExpr original = new Ast.IndexExpr(originalReceiver, new Ast.NameExpr("key"));
        Ast.IndexExpr forbidden = new Ast.IndexExpr(lookalikeReceiver, new Ast.NameExpr("key"));
        Ast.CheckedIndexKeys.record(original, Set.of("public"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(original, forbidden));
        assertEquals(false, Ast.CheckedIndexKeys.hasProjectionProof(forbidden));

        Ast.IndexExpr legitimate = new Ast.IndexExpr(originalReceiver, new Ast.NameExpr("key"));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.propagate(original, legitimate));
        assertEquals(Set.of("public"), Ast.CheckedIndexKeys.requirePlan(legitimate));
    }

    @Test
    void structurallyEqualButDistinctTupleReceiverIsNotAProof() {
        Ast.Expr originalReceiver = new Ast.MemberExpr(new Ast.NameExpr("context"), "tuple");
        Ast.Expr lookalikeReceiver = new Ast.MemberExpr(new Ast.NameExpr("context"), "tuple");
        assertEquals(originalReceiver, lookalikeReceiver);
        assertNotSame(originalReceiver, lookalikeReceiver);
        Ast.IndexExpr original = new Ast.IndexExpr(originalReceiver, new Ast.NameExpr("position"));
        Ast.IndexExpr forbidden = new Ast.IndexExpr(lookalikeReceiver, new Ast.NameExpr("position"));
        Ast.CheckedIndexKeys.recordTupleSlot(original, 0);
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.propagate(original, forbidden));
        assertEquals(null, Ast.CheckedIndexKeys.knownTupleSlot(forbidden));

        Ast.IndexExpr legitimate = new Ast.IndexExpr(originalReceiver, new Ast.LiteralExpr(0L));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.propagate(original, legitimate));
        assertEquals(0, Ast.CheckedIndexKeys.knownTupleSlot(legitimate));
    }

    @Test
    void tupleIndexedWriteRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  let mut pair = (1, 2);
                  pair[0] = 3;
                  return;
                }
                """)));
    }

    @Test
    void structuralFieldProofCannotFallThroughToRuntimeSequenceRead() {
        Ast.IndexExpr structural = new Ast.IndexExpr(
                new Ast.NameExpr("record"), new Ast.NameExpr("key"));
        Ast.CheckedIndexKeys.record(structural, Set.of("id"));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.requireTupleSlot(structural, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.requireTupleSlot(structural, 3));

        Ast.IndexExpr validSequence = new Ast.IndexExpr(
                new Ast.NameExpr("items"), new Ast.LiteralExpr(0L));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.requireTupleSlot(validSequence, 0));

        Ast.IndexExpr positional = new Ast.IndexExpr(
                new Ast.NameExpr("tuple"), new Ast.NameExpr("index"));
        Ast.CheckedIndexKeys.recordTupleSlot(positional, 1);
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.requireTupleSlot(positional, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.requireTupleSlot(positional, 0));
    }

    @Test
    void fieldProofRequiresExactAstIdentity() {
        Ast.IndexExpr first = new Ast.IndexExpr(
                new Ast.NameExpr("data"), new Ast.LiteralExpr("x"));
        Ast.IndexExpr second = new Ast.IndexExpr(
                new Ast.NameExpr("data"), new Ast.LiteralExpr("x"));
        Ast.CheckedIndexKeys.record(first, Set.of("x"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.recordTupleSlot(first, 0));
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.require(first, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.require(first, "secret"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.require(second, "x"));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.record(first, Set.of("secret")));
    }

    @Test
    void visibleProofSurvivesOptimizerCloneWithoutWidening() {
        Ast.IndexExpr site = new Ast.IndexExpr(
                new Ast.NameExpr("point"), new Ast.NameExpr("field"));
        Ast.CheckedIndexKeys.record(site, Set.of("x"));
        Ast.IndexExpr optimized = new Ast.IndexExpr(
                site.receiver(), new Ast.NameExpr("field"));
        Ast.CheckedIndexKeys.propagate(site, optimized);
        assertDoesNotThrow(() -> Ast.CheckedIndexKeys.require(optimized, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Ast.CheckedIndexKeys.require(optimized, "secret"));

        Ast.IndexExpr tuple = new Ast.IndexExpr(
                new Ast.NameExpr("values"), new Ast.LiteralExpr(0L));
        Ast.IndexExpr tupleRewritten = new Ast.IndexExpr(
                new Ast.NameExpr("values"), new Ast.LiteralExpr(0L));
        Ast.CheckedIndexKeys.propagate(tuple, tupleRewritten);
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedIndexKeys.require(tupleRewritten, "x"));
    }

    @Test
    void literalClassFieldAccessChecksVisibilityAndMethodNonReflection() {
        String declarations = """
                define class Candidate as
                  pub val int id = 9;
                  private val string secret = "hidden";
                  pub fnc score(): int { return 4; }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(declarations + """
                fnc ok(): int {
                  const instance = new Candidate();
                  return instance["id"];
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(declarations + """
                fnc bad(): string {
                  const instance = new Candidate();
                  return instance["secret"];
                }
                """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(declarations + """
                fnc bad(): int {
                  const instance = new Candidate();
                  return instance["score"];
                }
                """)));
    }

    @Test
    void heterogeneousDynamicIndexCannotPretendAllSlotsAreInt() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    fnc bad(string key): int {
                      const data = struct{x: int, y: string}{x: 3, y: "boo"};
                      return data[key];
                    }
                    """)));
        assertTrue(!failure.getMessage().isBlank());
    }
}

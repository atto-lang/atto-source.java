package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.DerivedCopyPlanV1;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Executable AttoLang guest copy tests. Require actual JUnit/GraalVM execution. */
final class DerivedCopyPlanGuestTest {
    private static String run(String source) throws Exception {
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source script = Source.newBuilder(OresLanguage.ID, source, "derived-copy-v1.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(script);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void ordinaryClassArrayFieldsAreClonedNotAliased() throws Exception {
        assertEquals("10|99", run("""
                define class Person as
                  pub let string name = "Alice";
                  pub val Array<int> scores = new Array<int>();
                end

                pub fnc main(): void {
                  let mut Person original = new Person();
                  original.scores.add(10);
                  let mut Person clone = rt copy original;
                  clone.scores.set(0, 99);
                  stdio.stdout.write(original.scores.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(clone.scores.get(0));
                  return;
                }
                """));
    }

    @Test
    void shallowBangHasDistinctAstFromLegacyDeepCopy() {
        String template = "fnc main(): int { val int x = 7; return %s; }";
        assertNotEquals(Parser.parse(template.formatted("rt copy x")),
                Parser.parse(template.formatted("copy! x")));
        assertEquals(Parser.parse(template.formatted("copy! x")),
                Parser.parse(template.formatted("copy!x")));
    }

    @Test
    void shallowBangCopiesScalarFieldClassAndTypedScalarArray() throws Exception {
        assertEquals("10|66|7|99", run("""
                define class Person as
                  pub let int score = 10;
                end
                pub fnc main(): void {
                  let mut Person original = new Person();
                  let mut Person clone = copy!original;
                  clone.score = 66;
                  stdio.stdout.write(original.score);
                  stdio.stdout.write("|");
                  stdio.stdout.write(clone.score);
                  let mut Array<int> values = new Array<int>();
                  values.add(7);
                  let mut Array<int> other = copy! values;
                  other.set(0, 99);
                  stdio.stdout.write("|");
                  stdio.stdout.write(values.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(other.get(0));
                  return;
                }
                """));
    }

    @Test
    void shallowBangRejectsOwnedMutableChildrenEvenWhenSequenceEmpty() {
        String source = """
                define class Person as
                  pub val Array<int> scores = new Array<int>();
                end
                pub fnc main(): void {
                  val Person original = new Person();
                  val Person clone = copy! original;
                  return;
                }
                """;
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_SHALLOW_ALIAS"), failure::toString);
        String array = """
                define class Person as
                  pub let int score = 10;
                end
                pub fnc main(): void {
                  val Array<Person> people = new Array<Person>();
                  val Array<Person> clone = copy! people;
                  return;
                }
                """;
        var arrayFailure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(array));
        assertTrue(arrayFailure.getMessage().contains("E_COPY_SHALLOW_ALIAS"), arrayFailure::toString);
    }

    @Test
    void flatDeepCopyRetainsInheritedFieldsWithoutAliasing() throws Exception {
        assertEquals("42|99|7", run("""
                define class Base as
                  pub let int value = 42;
                end
                define class Child extends Base as
                  pub let int tag = 7;
                end
                pub fnc main(): void {
                  let mut Child original = new Child();
                  let mut Child cloned = copy!! original;
                  cloned.value = 99;
                  stdio.stdout.write(original.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(cloned.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(cloned.tag);
                  return;
                }
                """));
    }

    @Test
    void doubleBangHasDistinctAstAndExecutesAcyclicDeepCopy() throws Exception {
        String template = "fnc main(): int { val int x = 7; return %s; }";
        assertNotEquals(Parser.parse(template.formatted("copy! x")),
                Parser.parse(template.formatted("copy!! x")));
        assertEquals(Parser.parse(template.formatted("copy!! x")),
                Parser.parse(template.formatted("copy!!x")));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(template.formatted("copy!!!x")));
        assertEquals("10|77", run("""
                define class Person as
                  pub val Array<int> scores = new Array<int>();
                end
                pub fnc main(): void {
                  let mut Person original = new Person();
                  original.scores.add(10);
                  let mut Person clone = copy!! original;
                  clone.scores.set(0, 77);
                  stdio.stdout.write(original.scores.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(clone.scores.get(0));
                  return;
                }
                """));
    }

    @Test
    void doubleBangStillRejectsUnprovedCyclicLayout() {
        String source = """
                define class Node as
                  pub let Node next;
                end
                fnc bad(Node value): Node {
                  return copy!! value;
                }
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        var error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(parsed));
        assertTrue(error.getMessage().contains("E_COPY_GRAPH_UNPROVEN")
                || error.getMessage().contains("E_COPY_TRANSITIVE"), error::toString);
    }

    @Test
    void shallowCapabilityDoesNotFollowOwnedMutableChildren() {
        String source = """
                define class Leaf as
                  pub let int value = 0;
                end
                define class Parent as
                  pub val Leaf child = new Leaf();
                end
                """;
        var parsed = assertDoesNotThrow(() -> Parser.parse(source));
        var classes = new java.util.HashMap<String, Ast.ClassDecl>();
        for (var module : parsed.modules()) {
            for (var declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) classes.put(klass.name(), klass);
            }
        }
        assertInstanceOf(DerivedCopyPlanV1.Instance.class,
                DerivedCopyPlanV1.deriveShallow(Ast.TypeRef.simple("Leaf"), classes::get));
        assertInstanceOf(DerivedCopyPlanV1.Instance.class,
                DerivedCopyPlanV1.derive(Ast.TypeRef.simple("Parent"), classes::get));
        var error = assertThrows(IllegalArgumentException.class, () ->
                DerivedCopyPlanV1.deriveShallow(Ast.TypeRef.simple("Parent"), classes::get));
        assertTrue(error.getMessage().contains("E_COPY_SHALLOW_ALIAS"));
    }

    @Test
    void rootTypedArrayCopyIsIndependentAndRetainsTypeForFurtherCopy() throws Exception {
        assertEquals("10|99|7", run("""
                pub fnc main(): void {
                  let mut Array<int> original = new Array<int>();
                  original.add(10);
                  let mut Array<int> copied = rt copy original;
                  copied.set(0, 99);
                  let mut Array<int> copied_twice = rt copy copied;
                  copied_twice.set(0, 7);
                  stdio.stdout.write(original.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(copied.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(copied_twice.get(0));
                  return;
                }
                """));
    }

    @Test
    void rootTypedListCopySeparatesMutableStorage() throws Exception {
        assertEquals("10|77", run("""
                pub fnc main(): void {
                  let mut List<int> original = new List<int>();
                  original.add(10);
                  let mut List<int> copied = rt copy original;
                  copied.set(0, 77);
                  stdio.stdout.write(original.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(copied.get(0));
                  return;
                }
                """));
    }

    @Test
    void rootTypedArrayOfClassesCopiesNestedObjects() throws Exception {
        assertEquals("10|88", run("""
                define class Child as
                  pub let int value = 10;
                end
                pub fnc main(): void {
                  let mut Array<Child> children = new Array<Child>();
                  children.add(new Child());
                  let mut Array<Child> clone = rt copy children;
                  clone[0].value = 88;
                  stdio.stdout.write(children.get(0).value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(clone.get(0).value);
                  return;
                }
                """));
    }

    @Test
    void legacyCopyOfChannelReferenceIsRejectedInsteadOfSharingHandle() {
        String source = """
                fnc bad(Channel<int> channel): Channel<int> {
                  return rt copy channel;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_UNPROVEN"), failure::toString);
    }

    @Test
    void nonCopyableArrayElementTypeRemainsRejectedEvenForEmptyArray() {
        String source = """
                pub fnc main(): void {
                  val Array<Channel<int>> channels = new Array<Channel<int>>();
                  val copied = rt copy channels;
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_UNPROVEN")
                || failure.getMessage().contains("E_COPY_TRANSITIVE"), failure::toString);
    }

    @Test
    void nestedClassesAreRecursivelyCloned() throws Exception {
        assertEquals("7|42", run("""
                define class Child as
                  pub let int value = 7;
                end
                define class Parent as
                  pub val Child child = new Child();
                end

                pub fnc main(): void {
                  let mut Parent original = new Parent();
                  let mut Parent clone = rt copy original;
                  clone.child.value = 42;
                  stdio.stdout.write(original.child.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(clone.child.value);
                  return;
                }
                """));
    }

    @Test
    void compilerDerivesInheritanceWithoutSlicing() throws Exception {
        assertEquals("5|12|99", run("""
                define class Base as
                  pub let int base_value = 5;
                end
                define class Child extends Base as
                  pub let int own_value = 12;
                end

                pub fnc main(): void {
                  let mut Child first = new Child();
                  let mut Child second = rt copy first;
                  second.base_value = 99;
                  stdio.stdout.write(first.base_value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.own_value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.base_value);
                  return;
                }
                """));
    }

    @Test
    void derivedPlanRejectsNestedResourceNotShallowCopy() {
        String source = """
                define class Connection as
                  pub let Channel<int> chan;
                end
                fnc bad(Connection value): Connection {
                  return rt copy value;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_TRANSITIVE"), failure::toString);
    }

    @Test
    void handwrittenCopyCannotBypassUnverifiedOverride() {
        String source = """
                define class Record as
                  pub let int n = 1;
                  pub copy() -> Record {
                    return self;
                  }
                end
                fnc bad(Record value): Record {
                  return rt copy value;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_OVERRIDE"), failure::toString);
    }

    @Test
    void recursiveClassLayoutsFailClosedUntilGraphCopierIsVerified() {
        String source = """
                define class Node as
                  pub let Node next;
                end
                fnc bad(Node value): Node {
                  return rt copy value;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("E_COPY_GRAPH_UNPROVEN")
                || failure.getMessage().contains("E_COPY_TRANSITIVE"), failure::toString);
    }

    @Test
    void branchingAcyclicClassPlansCannotExpandWithoutBound() {
        StringBuilder source = new StringBuilder("define class Leaf0 as pub let int value = 0; end\n");
        for (int i = 1; i <= 16; i++) {
            source.append("define class Leaf").append(i).append(" as pub let Leaf")
                    .append(i - 1).append(" left; pub let Leaf")
                    .append(i - 1).append(" right; end\n");
        }
        var program = assertDoesNotThrow(() -> Parser.parse(source.toString()));
        var classes = new java.util.HashMap<String, Ast.ClassDecl>();
        for (var module : program.modules()) {
            for (var declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) classes.put(klass.name(), klass);
            }
        }
        assertEquals(17, classes.size());
        assertDoesNotThrow(() -> DerivedCopyPlanV1.derive(Ast.TypeRef.simple("Leaf5"), classes::get));
        var error = assertThrows(IllegalArgumentException.class,
                () -> DerivedCopyPlanV1.derive(Ast.TypeRef.simple("Leaf16"), classes::get));
        assertTrue(error.getMessage().contains("E_COPY_PLAN_BUDGET"), error::toString);
    }

    @Test
    void scalarCopyRemainsAdmitted() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): int {
                  val int number = 42;
                  return rt copy number;
                }
                """));
    }

    @Test
    void copyPlanRejectsUnresolvedAndUnboundedCollectionElementTypes() {
        IllegalArgumentException unresolved = assertThrows(
                IllegalArgumentException.class, () -> DerivedCopyPlanV1.derive(
                        Ast.TypeRef.inferred(), name -> null));
        assertTrue(unresolved.getMessage().contains("E_COPY_UNPROVEN"));

        IllegalArgumentException raw = assertThrows(
                IllegalArgumentException.class, () -> DerivedCopyPlanV1.derive(
                        Ast.TypeRef.simple("Array"), name -> null));
        assertTrue(raw.getMessage().contains("E_COPY_UNPROVEN"));
    }
}

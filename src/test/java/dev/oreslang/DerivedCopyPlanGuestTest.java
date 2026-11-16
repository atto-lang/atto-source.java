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
                  pub let Array<int> scores = new Array<int>();
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
                  clone.get(0).value = 88;
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
                  pub let Child child = new Child();
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

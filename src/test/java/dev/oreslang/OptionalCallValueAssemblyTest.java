package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.OptionalCallAbiPreview;
import dev.oreslang.types.OptionalCallValueAssembly;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Value-level tests for the non-executable staged ABI boundary. These do NOT
 * imply guest named/optional call support: the compiler/runtime integration
 * and ownership proof remain deliberately disabled.
 */
final class OptionalCallValueAssemblyTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef BOOL = Ast.TypeRef.simple("bool");
    private static final Ast.TypeRef STRING = Ast.TypeRef.simple("string");
    private static final Ast.TypeRef OPTION_STRING =
            new Ast.TypeRef("Option", List.of(STRING), false);

    private record OptionBox(boolean present, Object value) { }

    private static final class Options implements OptionalCallValueAssembly.TrustedOptionFactory {
        final List<String> created = new ArrayList<>();

        @Override public Object some(Ast.TypeRef type, Object value) {
            assertEquals(STRING, type);
            assertNotNull(value);
            created.add("Some");
            return new OptionBox(true, value);
        }

        @Override public Object none(Ast.TypeRef type) {
            assertEquals(STRING, type);
            created.add("None");
            return new OptionBox(false, null);
        }

        @Override public boolean isNominalOption(Ast.TypeRef type, Object value) {
            assertEquals(STRING, type);
            return value instanceof OptionBox;
        }
    }

    private static OptionalCallAbiPreview.Slot slot(
            int index, Ast.TypeRef type, OptionalCallAbiPreview.Action action,
            int evaluatedSource, int rawCallItem) {
        return new OptionalCallAbiPreview.Slot(
                index, "arg" + index, type, action, evaluatedSource, rawCallItem);
    }

    @Test
    void explicitOmitConsumesRawItemButNotSourceTemporary() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr(3L), new Ast.LiteralExpr(true)),
                List.of(
                        slot(0, INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0),
                        slot(1, OPTION_STRING, OptionalCallAbiPreview.Action.INJECT_NONE, -1, 1),
                        slot(2, BOOL, OptionalCallAbiPreview.Action.PASS_REQUIRED, 1, 2)));
        Options factory = new Options();
        assertEquals(List.of(3L, new OptionBox(false, null), true),
                OptionalCallValueAssembly.assemble(preview, List.of(3L, true), factory));
        assertEquals(List.of("None"), factory.created);
    }

    @Test
    void namedReorderingMovesOnlyTheAbiSlots() {
        // Source evaluates c first, then a. Formal ABI is a, b, c.
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr(true), new Ast.LiteralExpr(3L)),
                List.of(
                        slot(0, INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 1, 1),
                        slot(1, OPTION_STRING, OptionalCallAbiPreview.Action.INJECT_NONE, -1, -1),
                        slot(2, BOOL, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0)));
        assertEquals(List.of(3L, new OptionBox(false, null), true),
                OptionalCallValueAssembly.assemble(
                        preview, List.of(true, 3L), new Options()));
    }

    @Test
    void suppliedNoneDoesNotCreateANewNoneOrDefault() {
        OptionBox suppliedNone = new OptionBox(false, null);
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr(3L), new Ast.NameExpr("None"),
                        new Ast.LiteralExpr(true)),
                List.of(
                        slot(0, INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0),
                        slot(1, OPTION_STRING, OptionalCallAbiPreview.Action.PASS_OPTION, 1, 1),
                        slot(2, BOOL, OptionalCallAbiPreview.Action.PASS_REQUIRED, 2, 2)));
        Options factory = new Options();
        List<Object> call = OptionalCallValueAssembly.assemble(
                preview, List.of(3L, suppliedNone, true), factory);
        assertSame(suppliedNone, call.get(1));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void suppliedStringGetsExactlyOneSome() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr("hello")),
                List.of(slot(0, OPTION_STRING, OptionalCallAbiPreview.Action.WRAP_SOME, 0, 0)));
        Options factory = new Options();
        assertEquals(List.of(new OptionBox(true, "hello")),
                OptionalCallValueAssembly.assemble(preview, List.of("hello"), factory));
        assertEquals(List.of("Some"), factory.created);
    }

    @Test
    void rejectsMismatchedSourceCountBeforeOptionFactoryCalled() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr(3L)),
                List.of(slot(0, OPTION_STRING, OptionalCallAbiPreview.Action.WRAP_SOME, 0, 0)));
        Options factory = new Options();
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(preview, List.of(), factory));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void rejectsJavaNullSourceBeforeAnyWrappingOrInjection() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr(3L), new Ast.LiteralExpr("unsafe")),
                List.of(
                        slot(0, OPTION_STRING, OptionalCallAbiPreview.Action.WRAP_SOME, 0, 0),
                        slot(1, OPTION_STRING, OptionalCallAbiPreview.Action.INJECT_NONE, -1, 1),
                        slot(2, OPTION_STRING, OptionalCallAbiPreview.Action.WRAP_SOME, 1, 2)));
        Options factory = new Options();
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(
                        preview, Arrays.asList(3L, null), factory));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void suppliedOptionMustBeNominalNotMerelyOptionTypedByMetadata() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.NameExpr("provided")),
                List.of(slot(0, OPTION_STRING,
                        OptionalCallAbiPreview.Action.PASS_OPTION, 0, 0)));
        Options factory = new Options();
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(
                        preview, List.of("not an Option"), factory));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void trustedFactoryCannotForgeSomeWithWrongRuntimeRepresentation() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr("hello")),
                List.of(slot(0, OPTION_STRING,
                        OptionalCallAbiPreview.Action.WRAP_SOME, 0, 0)));
        OptionalCallValueAssembly.TrustedOptionFactory invalid =
                new OptionalCallValueAssembly.TrustedOptionFactory() {
                    @Override public Object some(Ast.TypeRef type, Object value) {
                        return value;
                    }
                    @Override public Object none(Ast.TypeRef type) {
                        return new OptionBox(false, null);
                    }
                    @Override public boolean isNominalOption(Ast.TypeRef type, Object value) {
                        return value instanceof OptionBox;
                    }
                };
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(
                        preview, List.of("hello"), invalid));
    }

    @Test
    void requiredOptionRejectsPlainValueDespiteMatchingMetadata() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr("fake")),
                List.of(slot(0, OPTION_STRING,
                        OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0)));
        Options factory = new Options();
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(
                        preview, List.of("fake"), factory));
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void requiredOptionNonePreservesItsExactRuntimeIdentity() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.NameExpr("None")),
                List.of(slot(0, OPTION_STRING,
                        OptionalCallAbiPreview.Action.PASS_REQUIRED, 0, 0)));
        OptionBox explicitNone = new OptionBox(false, null);
        Options factory = new Options();
        assertSame(explicitNone, OptionalCallValueAssembly.assemble(
                preview, List.of(explicitNone), factory).getFirst());
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void invalidSuppliedOptionFailsBeforeEarlierSomeIsMaterialized() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.LiteralExpr("first"),
                        new Ast.LiteralExpr("forged")),
                List.of(
                        slot(0, OPTION_STRING,
                                OptionalCallAbiPreview.Action.WRAP_SOME, 0, 0),
                        slot(1, OPTION_STRING,
                                OptionalCallAbiPreview.Action.PASS_OPTION, 1, 1)));
        Options factory = new Options();
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(
                        preview, List.of("first", "forged"), factory));
        assertTrue(factory.created.isEmpty(),
                "no Some/None allocations before supplied Option validation");
    }

    @Test
    void rejectsNullNominalOptionObjectsReturnedByFactory() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(), List.of(
                        slot(0, OPTION_STRING, OptionalCallAbiPreview.Action.INJECT_NONE, -1, -1)));
        OptionalCallValueAssembly.TrustedOptionFactory invalid =
                new OptionalCallValueAssembly.TrustedOptionFactory() {
                    @Override public Object some(Ast.TypeRef type, Object value) { return null; }
                    @Override public Object none(Ast.TypeRef type) { return null; }
                    @Override public boolean isNominalOption(Ast.TypeRef type, Object value) {
                        return value instanceof OptionBox;
                    }
                };
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(preview, List.of(), invalid));
    }
    @Test
    void callbackMutationCannotSwapNominalOptionAfterPreflight() {
        OptionBox validated = new OptionBox(false, null);
        var supplied = new ArrayList<Object>(List.of(validated, 7L));
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.NameExpr("supplied"), new Ast.LiteralExpr(7L)),
                List.of(
                        slot(0, OPTION_STRING, OptionalCallAbiPreview.Action.PASS_OPTION, 0, 0),
                        slot(1, INT, OptionalCallAbiPreview.Action.PASS_REQUIRED, 1, 1)));
        OptionalCallValueAssembly.TrustedOptionFactory adversarial =
                new OptionalCallValueAssembly.TrustedOptionFactory() {
                    @Override public Object some(Ast.TypeRef type, Object value) {
                        throw new AssertionError("no Some expected");
                    }
                    @Override public Object none(Ast.TypeRef type) {
                        throw new AssertionError("no None expected");
                    }
                    @Override public boolean isNominalOption(Ast.TypeRef type, Object value) {
                        supplied.set(0, "forged-after-validation");
                        supplied.set(1, -1L);
                        return value == validated;
                    }
                };
        var result = OptionalCallValueAssembly.assemble(preview, supplied, adversarial);
        assertSame(validated, result.get(0),
                "verified Option identity must remain stable through ABI materialization");
        assertEquals(7L, result.get(1),
                "later scalar ABI slot must also use the original value snapshot");
        assertEquals(List.of("forged-after-validation", -1L), supplied,
                "adversarial callback really changed the caller-owned list");
    }

    @Test
    void nullOrMissingSourceTemporaryCannotReachNominalAdapter() {
        var preview = new OptionalCallAbiPreview.Preview(
                List.of(new Ast.NameExpr("supplied")),
                List.of(slot(0, OPTION_STRING, OptionalCallAbiPreview.Action.PASS_OPTION, 0, 0)));
        Options factory = new Options();
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(
                        preview, Arrays.asList((Object) null), factory));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallValueAssembly.assemble(preview, List.of(), factory));
        assertTrue(factory.created.isEmpty());
    }

}

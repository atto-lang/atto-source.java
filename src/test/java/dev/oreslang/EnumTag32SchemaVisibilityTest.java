package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EnumCaseSourceBindings;
import dev.oreslang.types.EnumTag32Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable only as a host-unit test. A parsed nullary value/tag layout is
 * not linked, type-checked or safe to dispatch as a guest enum yet.
 */
final class EnumTag32SchemaVisibilityTest {
    private static final List<EnumTag32Plan.CaseTag> CASES = List.of(
            new EnumTag32Plan.CaseTag("A", 5),
            new EnumTag32Plan.CaseTag("B", 17),
            new EnumTag32Plan.CaseTag("C", 31));

    @Test
    void canonicalTagDigestPinsVisibilityAndAgreesWithNativeSchemaByteVector() {
        EnumTag32Plan hidden = new EnumTag32Plan("local_unit.__root__.Foo", CASES);
        EnumTag32Plan publicEnum = new EnumTag32Plan("local_unit.__root__.Foo", CASES, true);
        assertFalse(hidden.exported());
        assertTrue(publicEnum.exported());
        assertEquals("1ac2d094a38482e8f21ada69339af2ad713192183980a1e5238065d1a61dfe83",
                hidden.schemaFingerprint());
        assertEquals("fb3c1db90ede565be35ba45be4ef9d7f47655e87b8ef7dcbb74136b8459dc4fd",
                publicEnum.schemaFingerprint());
        assertEquals(17, hidden.constantTag("B"));
        assertEquals(hidden.constantTag("B"), publicEnum.constantTag("B"));
        assertNotEquals(hidden.schemaFingerprint(), publicEnum.schemaFingerprint());
        hidden.requireExactSchema(hidden.schemaFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> publicEnum.requireExactSchema(hidden.schemaFingerprint()));
        assertThrows(IllegalArgumentException.class,
                () -> hidden.requireExactSchema(publicEnum.schemaFingerprint()));
    }

    @Test
    void parsedDeclarationExportFlagFlowsToOfflineNullaryTagLayout() {
        Ast.Program parsed = Parser.parse("""
                define enum Internal as A = 5, B = 17, C = 31 end
                define pub enum External as A = 5, B = 17, C = 31 end
                """);
        EnumCaseSourceBindings.Proof proof =
                EnumCaseSourceBindings.compileLocalProof(parsed);
        EnumTag32Plan privatePlan = proof.nullaryTagLayouts()
                .get("local_unit.__root__.Internal");
        EnumTag32Plan publicPlan = proof.nullaryTagLayouts()
                .get("local_unit.__root__.External");
        assertNotNull(privatePlan);
        assertNotNull(publicPlan);
        assertFalse(privatePlan.exported());
        assertTrue(publicPlan.exported());
        assertEquals(5, publicPlan.constantTag("A"));
        assertEquals(31, privatePlan.constantTag("C"));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(parsed),
                "offline nullary layouts must not bypass executable checker");
    }

    @Test
    void stagedJavaAndNativeAotLayoutsRejectUnportableUnicodeIds() {
        // The general Java lexer may allow these, but portable AOT cannot
        // silently create an identity not representable by the native stage.
        assertThrows(IllegalArgumentException.class,
                () -> new EnumTag32Plan.CaseTag("État", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new EnumTag32Plan("root.Café", CASES, true));
        assertThrows(IllegalArgumentException.class,
                () -> new EnumTag32Plan("roöt.Foo", CASES));
        assertDoesNotThrow(() -> new EnumTag32Plan(
                "root._Foo42", CASES, true));
    }

    @Test
    void tagOrNominalIdentityChangeInvalidatesCachedSchema() {
        EnumTag32Plan old = new EnumTag32Plan("root.Foo", CASES, true);
        EnumTag32Plan newTag = new EnumTag32Plan("root.Foo", List.of(
                new EnumTag32Plan.CaseTag("A", 5),
                new EnumTag32Plan.CaseTag("B", 18),
                new EnumTag32Plan.CaseTag("C", 31)), true);
        EnumTag32Plan renamed = new EnumTag32Plan("root.Bar", CASES, true);
        assertNotEquals(old.schemaFingerprint(), newTag.schemaFingerprint());
        assertNotEquals(old.schemaFingerprint(), renamed.schemaFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> newTag.requireExactSchema(old.schemaFingerprint()));
    }
}

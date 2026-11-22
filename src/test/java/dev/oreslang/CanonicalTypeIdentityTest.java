package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.CanonicalTypeIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalTypeIdentityTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test void structuralPermutationsHashIdenticallyButCapabilitiesRemainDistinct() {
        var intType = Ast.TypeRef.simple("int");
        var string = Ast.TypeRef.simple("String");
        var left = Ast.TypeRef.recordType(Map.of("alpha", intType, "beta", string));
        var right = Ast.TypeRef.recordType(Map.of("beta", string, "alpha", intType));
        assertEquals(id(left), id(right));

        var mutable = Ast.TypeRef.borrowed(left, true);
        var immutable = Ast.TypeRef.borrowed(left, false);
        assertNotEquals(id(mutable), id(immutable));

        var readonly = Ast.TypeRef.readonlyRecordType(Map.of("alpha", intType, "beta", string));
        assertNotEquals(id(left), id(readonly));

        assertEquals(id(new Ast.TypeRef("$union$", List.of(intType, string), false)),
                id(new Ast.TypeRef("$union$", List.of(string, intType), false)));
    }

    @Test void nominalProviderChangesInvalidateTypeIdentity() {
        var ref = new Ast.TypeRef("Box", List.of(Ast.TypeRef.simple("int")), false);
        var first = new CanonicalTypeIdentity.Nominal("module.ores", "pkg", "Box", A);
        var second = new CanonicalTypeIdentity.Nominal("module.ores", "pkg", "Box", B);
        var fingerprint = CanonicalTypeIdentity.of(ref, Map.of("Box", first), List.of());
        var changed = CanonicalTypeIdentity.of(ref, Map.of("Box", second), List.of());
        assertNotEquals(fingerprint.sha256(), changed.sha256());
        assertArrayEquals(fingerprint.bytes(),
                CanonicalTypeIdentity.of(ref, Map.of("Box", first), List.of()).bytes());
        byte[] leaked = fingerprint.bytes();
        leaked[0] = 0;
        assertNotEquals(0, fingerprint.bytes()[0]);
    }

    @Test void genericBindersCanonicalizeAlphaRenamesButNotPositions() {
        assertEquals(CanonicalTypeIdentity.of(Ast.TypeRef.simple("T"), Map.of(),
                List.of("T")).sha256(),
                CanonicalTypeIdentity.of(Ast.TypeRef.simple("U"), Map.of(),
                        List.of("U")).sha256());
        assertNotEquals(CanonicalTypeIdentity.of(Ast.TypeRef.simple("T"), Map.of(),
                List.of("T", "U")).sha256(),
                CanonicalTypeIdentity.of(Ast.TypeRef.simple("U"), Map.of(),
                        List.of("T", "U")).sha256());
    }

    @Test void unresolvedAndTooDeepTypeTreesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> id(Ast.TypeRef.inferred()));
        assertThrows(IllegalArgumentException.class, () -> id(Ast.TypeRef.simple("Unknown")));
        assertThrows(IllegalArgumentException.class, () -> id(Ast.TypeRef.simple("External")));
        assertThrows(IllegalArgumentException.class, () -> id(new Ast.TypeRef(
                "External", List.of(Ast.TypeRef.simple("int")), true)));
        assertThrows(IllegalArgumentException.class, () -> id(
                new Ast.TypeRef("$union$", List.of(Ast.TypeRef.simple("int"),
                        Ast.TypeRef.simple("int")), false)));
        Ast.TypeRef current = Ast.TypeRef.simple("int");
        for (int i = 0; i < 100; i++) current = Ast.TypeRef.borrowed(current, false);
        Ast.TypeRef tooDeep = current;
        assertThrows(IllegalArgumentException.class, () -> id(tooDeep));
    }

    private static String id(Ast.TypeRef type) {
        return CanonicalTypeIdentity.of(type, Map.of(), List.of()).sha256();
    }
}

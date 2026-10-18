package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.RefinementRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RefinementRulesTest {
    private Ast.TypeAliasDecl alias(String source) {
        return (Ast.TypeAliasDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
    }

    @Test void generatedDescriptorIsCanonicalAndFailClosed() {
        var a = alias("@Min(1) @Max(65535) type Port = int;");
        var b = alias("type Port = int with value >= 1 and value <= 65535;");
        var da = RefinementRules.descriptor(null, a.target(), a.refinement());
        var db = RefinementRules.descriptor(null, b.target(), b.refinement());
        assertEquals(da, db);
        assertEquals(64, da.fingerprint().length());
        assertEquals("6162f74ca5d074640b2415eae37297ce299c6e1d1c77b6e9afb76ad686049cd2", da.fingerprint());
        assertTrue(da.accepts(1L));
        assertTrue(da.accepts(65535L));
        assertFalse(da.accepts(0L));
        assertFalse(da.accepts(65536L));
        assertFalse(da.accepts(1.0));
        var n = alias("@MinLength(1) define newtype Username(string);");
        assertThrows(IllegalArgumentException.class,
                () -> RefinementRules.descriptor("Username", n.target(), n.refinement()));
        var one = RefinementRules.descriptor("app.Username", n.target(), n.refinement());
        var two = RefinementRules.descriptor("other.Username", n.target(), n.refinement());
        assertNotEquals(one.fingerprint(), two.fingerprint());
        assertEquals("8b8a65194fb051d69d27590862fe4522d5e8be3dcfb263e1445f8982f5203540", one.fingerprint());
        assertFalse(one.accepts(String.valueOf((char) 0xD800)));
    }

    @Test void publicDescriptorRejectsForgedMetadataAndNoncanonicalHoles() {
        var aliased = alias("type Port = int with value gte 1 and value lte 65535;");
        var trusted = RefinementRules.descriptor(null, aliased.target(), aliased.refinement());
        assertThrows(IllegalArgumentException.class,
                () -> new RefinementRules.Descriptor(1, "int", "value", 1L,
                        65535L, java.util.List.of(), "", "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new RefinementRules.Descriptor(1, "int", "length", 1L,
                        65535L, java.util.List.of(), "", trusted.fingerprint()));
        assertThrows(IllegalArgumentException.class,
                () -> new RefinementRules.Descriptor(2, trusted.base(), trusted.property(),
                        trusted.min(), trusted.max(), trusted.excluded(),
                        trusted.nominalIdentity(), trusted.fingerprint()));
        assertThrows(IllegalArgumentException.class,
                () -> new RefinementRules.Descriptor(1, "int", "value", 1L,
                        65535L, java.util.List.of(3L, 2L), "", trusted.fingerprint()));
        assertThrows(IllegalArgumentException.class,
                () -> new RefinementRules.Descriptor(1, "int", "value", 1L,
                        65535L, java.util.List.of(0L), "", trusted.fingerprint()));
        assertThrows(IllegalArgumentException.class,
                () -> new RefinementRules.Descriptor(1, "string", "length", -1L,
                        2L, java.util.List.of(), "", trusted.fingerprint()));
        assertDoesNotThrow(() -> new RefinementRules.Descriptor(trusted.version(),
                trusted.base(), trusted.property(), trusted.min(), trusted.max(),
                trusted.excluded(), trusted.nominalIdentity(), trusted.fingerprint()));
    }

    @Test void offDomainExclusionsDoNotAlterGeneratedSchema() {
        var plain = alias("type Port = int with value gte 1 and value lte 65535;");
        var redundant = alias(
                "type Port = int with value gte 1 and value lte 65535 and value ne 0 and value ne 65536;");
        assertEquals(
                RefinementRules.descriptor(null, plain.target(), plain.refinement()),
                RefinementRules.descriptor(null, redundant.target(), redundant.refinement()));
        assertTrue(RefinementRules.normalize(
                redundant.target(), redundant.refinement().bounds()).excluded().isEmpty());
    }

    @Test void proveStrongerToWeakerButNotReverse() {
        Ast.TypeAliasDecl exact = alias("type Exact = string with length eq 10;");
        Ast.TypeAliasDecl nonEmpty = alias("type NonEmpty = string with length gt 0;");
        assertTrue(RefinementRules.entails(
                exact.name(), exact.target(), exact.refinement(),
                nonEmpty.name(), nonEmpty.target(), nonEmpty.refinement()));
        assertFalse(RefinementRules.entails(
                nonEmpty.name(), nonEmpty.target(), nonEmpty.refinement(),
                exact.name(), exact.target(), exact.refinement()));
    }

    @Test void nominalIdentityCannotEraseOrConvert() {
        Ast.TypeAliasDecl a = alias("define newtype A(string) with length gt 0;");
        Ast.TypeAliasDecl b = alias("define newtype B(string) with length gt 0;");
        Ast.TypeAliasDecl plain = alias("type NonEmpty = string with length gt 0;");
        assertFalse(RefinementRules.entails(
                a.name(), a.target(), a.refinement(), b.name(), b.target(), b.refinement()));
        assertFalse(RefinementRules.entails(
                a.name(), a.target(), a.refinement(), plain.name(), plain.target(), plain.refinement()));
        assertFalse(RefinementRules.entails(
                a.name(), a.target(), a.refinement(), a.name(), a.target(), a.refinement()));
        assertTrue(RefinementRules.entails(
                "app.A", a.target(), a.refinement(), "app.A", a.target(), a.refinement()));
        assertThrows(IllegalArgumentException.class,
                () -> RefinementRules.tryFrom(a.name(), a.target(), a.refinement(), "ok"));
        var checked = RefinementRules.tryFrom("app.A", a.target(), a.refinement(), "ok");
        assertTrue(checked.ok());
        assertInstanceOf(RefinementRules.NominalValue.class, checked.value());
        assertEquals("ok", ((RefinementRules.NominalValue)checked.value()).intoInner());
        assertEquals("app.A", ((RefinementRules.NominalValue)checked.value()).qualifiedTypeName());
        var wrapped = (RefinementRules.NominalValue) checked.value();
        assertEquals(
                RefinementRules.descriptor("app.A", a.target(), a.refinement()).fingerprint(),
                wrapped.schemaFingerprint());
        var other = RefinementRules.tryFrom(
                "other.A", a.target(), a.refinement(), "ok");
        assertNotEquals(
                wrapped.schemaFingerprint(),
                ((RefinementRules.NominalValue) other.value()).schemaFingerprint());
        assertTrue(java.util.Arrays.stream(RefinementRules.NominalValue.class.getDeclaredConstructors())
                .allMatch(ctor -> java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())));

    }

    @Test void canonicalStringSpellingHasIdenticalProofAndSchemaSemantics() {
        Ast.TypeAliasDecl lower = alias("type Lower = string with length gt 0;");
        Ast.TypeAliasDecl host = alias("type Host = String with length gt 0;");
        assertTrue(RefinementRules.entails(
                lower.name(), lower.target(), lower.refinement(),
                host.name(), host.target(), host.refinement()));
        assertTrue(RefinementRules.entails(
                host.name(), host.target(), host.refinement(),
                lower.name(), lower.target(), lower.refinement()));
        assertEquals(
                RefinementRules.descriptor(null, lower.target(), lower.refinement()),
                RefinementRules.descriptor(null, host.target(), host.refinement()));
    }

    @Test void realUnicodeScalarsNotUtf16Units() {
        Ast.TypeAliasDecl exactOne = alias("type One = string with length eq 1;");
        assertTrue(RefinementRules.accepts(exactOne.target(), exactOne.refinement(), "😀"));
        assertFalse(RefinementRules.accepts(exactOne.target(), exactOne.refinement(), "e\u0301"));
        assertEquals(1, RefinementRules.unicodeScalarCount("😀"));
        assertEquals(2, RefinementRules.unicodeScalarCount("e\u0301"));
        assertThrows(IllegalArgumentException.class,
                () -> RefinementRules.unicodeScalarCount(String.valueOf((char)0xD800)));
        assertThrows(IllegalArgumentException.class,
                () -> RefinementRules.unicodeScalarCount(String.valueOf((char)0xDC00)));
        for (char invalid : new char[] { (char) 0xD800, (char) 0xDC00 }) {
            String source = String.valueOf(invalid);
            assertFalse(RefinementRules.accepts(exactOne.target(), exactOne.refinement(), source));
            assertFalse(RefinementRules.tryFrom(
                    exactOne.name(), exactOne.target(), exactOne.refinement(), source).ok());
        }
    }

    @Test void runtimeCheckedConstructionRejectsInvalidValue() {
        Ast.TypeAliasDecl nonempty = alias("@NotEmpty type NonEmpty = string;");
        assertFalse(RefinementRules.tryFrom(
                nonempty.name(),nonempty.target(),nonempty.refinement(),"").ok());
        assertTrue(RefinementRules.tryFrom(
                nonempty.name(),nonempty.target(),nonempty.refinement(),"hi").ok());
    }

    @Test void holesAtMaxDoNotOverflowAndContradictionsFail() {
        var one = alias("type One = int with value eq 9223372036854775807;");
        var range = RefinementRules.normalize(one.target(), one.refinement().bounds());
        assertTrue(range.contains(Long.MAX_VALUE));
        assertFalse(range.contains(Long.MAX_VALUE - 1));
        assertThrows(IllegalArgumentException.class, () ->
                RefinementRules.normalize(Ast.TypeRef.simple("int"), java.util.List.of(
                        new Ast.RefinementBound("value","gte",Long.MAX_VALUE-1),
                        new Ast.RefinementBound("value","ne",Long.MAX_VALUE-1),
                        new Ast.RefinementBound("value","ne",Long.MAX_VALUE))));
    }

    @Test void minimumSignedInt64BoundAndImpossibleNeighbors() {
        Ast.TypeAliasDecl min = alias("type Min = int with value eq -9223372036854775808;");
        var interval = RefinementRules.normalize(min.target(), min.refinement().bounds());
        assertEquals(Long.MIN_VALUE, interval.min());
        assertEquals(Long.MIN_VALUE, interval.max());
        assertTrue(interval.contains(Long.MIN_VALUE));
        assertFalse(interval.contains(Long.MIN_VALUE + 1));
        assertTrue(RefinementRules.accepts(min.target(), min.refinement(), Long.MIN_VALUE));

        Ast.TypeAliasDecl minSymbol = alias("type Min = int with value >= -9223372036854775808 and value <= -9223372036854775808;");
        assertEquals(interval, RefinementRules.normalize(minSymbol.target(), minSymbol.refinement().bounds()));
        assertThrows(IllegalArgumentException.class, () ->
                alias("type Bad = int with value lt -9223372036854775808;"));
        assertThrows(IllegalArgumentException.class, () ->
                alias("type Bad = int with value eq -9223372036854775809;"));
        assertThrows(IllegalArgumentException.class, () ->
                alias("type Bad = int with value eq 9223372036854775808;"));
    }

    @Test void excludedIntegerIntervalsCannotBeEmpty() {
        assertThrows(IllegalArgumentException.class, () ->
                alias("type Empty = int with value gte 3 and value lte 4 and value ne 3 and value ne 4;"));
        assertThrows(IllegalArgumentException.class, () ->
                alias("type Empty = int with value gte -9223372036854775808 and value lte -9223372036854775807 and value ne -9223372036854775808 and value ne -9223372036854775807;"));
    }

    @Test void unverifiedWhereIsNotExecutable() {
        Ast.TypeAliasDecl where = alias("type NonBlank = string where it.trim().length > 0;");
        assertThrows(UnsupportedOperationException.class,
                () -> RefinementRules.tryFrom(where.name(),where.target(),where.refinement(),"ok"));
    }

    @Test void identicalWhereSourceIsNotAnImplicationProof() {
        Ast.TypeAliasDecl a = alias("type A = string where it.trim().length > 0;");
        Ast.TypeAliasDecl b = alias("type B = string where it.trim().length > 0;");
        assertFalse(RefinementRules.entails(
                a.name(), a.target(), a.refinement(),
                b.name(), b.target(), b.refinement()));
        assertFalse(RefinementRules.entails(
                a.name(), a.target(), a.refinement(),
                a.name(), a.target(), a.refinement()));
    }
}

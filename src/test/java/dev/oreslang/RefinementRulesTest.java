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
        assertTrue(RefinementRules.entails(
                a.name(), a.target(), a.refinement(), a.name(), a.target(), a.refinement()));
        var checked = RefinementRules.tryFrom(a.name(), a.target(), a.refinement(), "ok");
        assertTrue(checked.ok());
        assertInstanceOf(RefinementRules.NominalValue.class, checked.value());
        assertEquals("ok", ((RefinementRules.NominalValue)checked.value()).intoInner());
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
                        new Ast.RefinementBound("value","ge",Long.MAX_VALUE-1),
                        new Ast.RefinementBound("value","ne",Long.MAX_VALUE-1),
                        new Ast.RefinementBound("value","ne",Long.MAX_VALUE))));
    }

    @Test void unverifiedWhereIsNotExecutable() {
        Ast.TypeAliasDecl where = alias("type NonBlank = string where it.trim().length > 0;");
        assertThrows(UnsupportedOperationException.class,
                () -> RefinementRules.tryFrom(where.name(),where.target(),where.refinement(),"ok"));
    }
}

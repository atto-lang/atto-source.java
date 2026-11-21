package dev.oreslang.types;

import dev.oreslang.types.DefaultCallShapeV3.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SelectedCallSnapshotV3Test {
    private static SelectedCallSnapshotV3.Target target(String digest) {
        return new SelectedCallSnapshotV3.Target("instance-method", "m.Foo", "bar", 3, digest);
    }
    private static Formal plain(String n) { return new Formal(n, false, false, null); }
    private static Formal defaulted(String n) {
        return new Formal(n, false, true, new Constant(ConstantKind.INTEGER, 5L));
    }

    @Test void verifiedTargetAndLexicalPermutationRoundTrip() {
        var selected = target("a".repeat(64));
        List<Formal> params = List.of(plain("a"), defaulted("b"));
        List<Item> provided = List.of(Item.value("b"), Item.value("a"));
        var receipt = SelectedCallSnapshotV3.bind(selected, params, provided);
        assertEquals(List.of(1, 0), receipt.plan().evaluatedRawIndices().stream()
                .map(i -> receipt.plan().abiSlots().get(i).rawItemIndex()).toList());
        assertDoesNotThrow(() -> receipt.verify(selected, params, provided));
        assertEquals(64, receipt.fingerprint().length());
    }

    @Test void replayAgainstOtherAbiOrDeclarationIsRejected() {
        var selected = target("a".repeat(64));
        List<Formal> params = List.of(plain("a"), defaulted("b"));
        List<Item> provided = List.of(Item.value("a"));
        var receipt = SelectedCallSnapshotV3.bind(selected, params, provided);
        assertThrows(IllegalArgumentException.class,
                () -> receipt.verify(target("b".repeat(64)), params, provided));
        assertThrows(IllegalArgumentException.class, () -> receipt.verify(
                new SelectedCallSnapshotV3.Target("static-function", "m.Foo", "bar", 3, "a".repeat(64)),
                params, provided));
    }

    @Test void replayAgainstChangedNamesOrDefaultValueIsRejected() {
        var selected = target("a".repeat(64));
        List<Formal> params = List.of(plain("a"), defaulted("b"));
        List<Item> provided = List.of(Item.value("a"));
        var receipt = SelectedCallSnapshotV3.bind(selected, params, provided);
        assertThrows(IllegalArgumentException.class, () -> receipt.verify(
                selected,
                List.of(plain("a"), new Formal("b", false, true,
                        new Constant(ConstantKind.INTEGER, 6L))), provided));
        assertThrows(IllegalArgumentException.class, () -> receipt.verify(
                selected, params, List.of(Item.value("b"))));
        assertThrows(IllegalArgumentException.class, () -> receipt.verify(
                selected, List.of(plain("renamed"), defaulted("b")), provided));
    }

    @Test void omissionMarkersAreCoveredByReceiptFingerprint() {
        var selected = target("a".repeat(64));
        List<Formal> formals = List.of(defaulted("a"), defaulted("b"));
        var initial = SelectedCallSnapshotV3.bind(selected, formals,
                List.of(Item.omit("a"), Item.value("b")));
        assertDoesNotThrow(() -> initial.verify(
                selected, formals, List.of(Item.omit("a"), Item.value("b"))));
        assertThrows(IllegalArgumentException.class, () -> initial.verify(
                selected, formals, List.of(Item.value("a"), Item.value("b"))));
    }

    @Test void differentAstOccurrencesCannotReuseSameShapeProof() {
        var target = target("a".repeat(64));
        List<Formal> params = List.of(plain("a"));
        List<Item> items = List.of(Item.value(null));
        var firstCall = new dev.oreslang.ast.Ast.NameExpr("call");
        var otherCall = new dev.oreslang.ast.Ast.NameExpr("call");
        var receipt = SelectedCallSnapshotV3.bindForCallSite(
                target, firstCall, params, items);
        assertDoesNotThrow(() -> receipt.verifyForCallSite(
                firstCall, target, params, items));
        // Downgrading an AST-bound plan to weak shape-only verification must fail.
        assertThrows(IllegalArgumentException.class, () ->
                receipt.verify(target, params, items));
        assertThrows(IllegalArgumentException.class, () ->
                receipt.verifyForCallSite(otherCall, target, params, items));
        var shapeOnly = SelectedCallSnapshotV3.bind(target, params, items);
        assertThrows(IllegalArgumentException.class, () ->
                shapeOnly.verifyForCallSite(firstCall, target, params, items));
        assertThrows(NullPointerException.class, () ->
                SelectedCallSnapshotV3.bindForCallSite(target, null, params, items));
    }

    @Test void malformedUtf16MustNotCollapseToReplacementBytes() {
        var target = target("a".repeat(64));
        // Java's default UTF-8 encoder would replace an unpaired surrogate
        // with '?' and produce the same digest as the actual '?' character.
        List<Formal> invalid = List.of(new Formal("x", false, true,
                new Constant(ConstantKind.STRING, "\uD800")));
        List<Formal> valid = List.of(new Formal("x", false, true,
                new Constant(ConstantKind.STRING, "?")));
        assertThrows(IllegalArgumentException.class, () ->
                SelectedCallSnapshotV3.bind(target, invalid, List.of()));
        assertDoesNotThrow(() ->
                SelectedCallSnapshotV3.bind(target, valid, List.of()));
        List<Formal> paired = List.of(new Formal("x", false, true,
                new Constant(ConstantKind.STRING, "\uD83D\uDE42")));
        assertDoesNotThrow(() ->
                SelectedCallSnapshotV3.bind(target, paired, List.of()));
    }

    @Test void invalidSignatureDigestIsNotAccepted() {
        assertThrows(IllegalArgumentException.class, () -> target("abc"));
        assertThrows(IllegalArgumentException.class, () -> target("A".repeat(64)));
    }
}

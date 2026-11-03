package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

final class MacroExportArtifactTest {
    private static Ast.MacroDecl macro(String body, boolean exported) {
        String source = "define " + (exported ? "pub " : "")
                + "macro identity(Syntax<Expr> value) -> Syntax<Expr> { "
                + body + " }";
        return Parser.parse(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.MacroDecl.class::isInstance)
                .map(Ast.MacroDecl.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static MacroExportArtifact replace(
            MacroExportArtifact original,
            String format,
            String semanticsAbi,
            String owner,
            String content,
            String digest) {
        return new MacroExportArtifact(
                format, semanticsAbi, owner, original.macroName(), content, digest);
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifiedArtifactIsCanonicalAcrossFormattingOnlyChanges() {
        Ast.MacroDecl compact = macro("return value;", true);
        Ast.MacroDecl spaced = Parser.parse("""
                define pub macro identity (
                    Syntax<Expr> value
                ) -> Syntax<Expr> {
                    return     value ;
                }
                """).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.MacroDecl.class::isInstance)
                .map(Ast.MacroDecl.class::cast)
                .findFirst()
                .orElseThrow();

        MacroExportArtifact artifact = MacroExportArtifact.fromDeclaration("Producer", compact);
        assertDoesNotThrow(() -> artifact.verifyAgainst("Producer", spaced));
        assertEquals(MacroCanonicalIdentity.FORMAT, artifact.format());
        assertEquals(MacroCanonicalIdentity.SEMANTICS_ABI, artifact.semanticsAbi());
        assertEquals(sha256(MacroCanonicalIdentity.encode(compact)), artifact.contentSha256());
    }

    @Test
    void rejectsWrongOwnerAndWrongDeclarationContentEvenWithRecomputedDigest() throws Exception {
        Ast.MacroDecl original = macro("return value;", true);
        Ast.MacroDecl changed = macro("return quote(7);", true);
        MacroExportArtifact artifact = MacroExportArtifact.fromDeclaration("Producer", original);

        IllegalArgumentException owner = assertThrows(
                IllegalArgumentException.class,
                () -> artifact.verifyAgainst("Other", original));
        assertTrue(owner.getMessage().contains("owner or declaration identity"));

        IllegalArgumentException changedSource = assertThrows(
                IllegalArgumentException.class,
                () -> artifact.verifyAgainst("Producer", changed));
        assertTrue(changedSource.getMessage().contains("bound compiler AST"));

        String tampered = artifact.canonicalContent() + "extra";
        MacroExportArtifact selfConsistentForgery = replace(
                artifact, artifact.format(), artifact.semanticsAbi(),
                artifact.ownerModule(), tampered, sha256(tampered));
        IllegalArgumentException forged = assertThrows(
                IllegalArgumentException.class,
                () -> selfConsistentForgery.verifyAgainst("Producer", original));
        assertTrue(forged.getMessage().contains("bound compiler AST"));
    }

    @Test
    void rejectsDigestTamperingAndUnsupportedArtifactVersions() {
        Ast.MacroDecl original = macro("return value;", true);
        MacroExportArtifact artifact = MacroExportArtifact.fromDeclaration("Producer", original);

        MacroExportArtifact badDigest = replace(
                artifact, artifact.format(), artifact.semanticsAbi(),
                artifact.ownerModule(), artifact.canonicalContent(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> badDigest.verifyAgainst("Producer", original));

        MacroExportArtifact badFormat = replace(
                artifact, "ores-macro-content-v999", artifact.semanticsAbi(),
                artifact.ownerModule(), artifact.canonicalContent(), artifact.contentSha256());
        assertThrows(IllegalArgumentException.class,
                () -> badFormat.verifyAgainst("Producer", original));

        MacroExportArtifact badAbi = replace(
                artifact, artifact.format(), "ores-macro-semantics-v999",
                artifact.ownerModule(), artifact.canonicalContent(), artifact.contentSha256());
        assertThrows(IllegalArgumentException.class,
                () -> badAbi.verifyAgainst("Producer", original));
    }

    @Test
    void privateMacroCannotBeExportedAndMalformedUnicodeFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> MacroExportArtifact.fromDeclaration("Producer", macro("return value;", false)));

        Ast.MacroDecl original = macro("return value;", true);
        MacroExportArtifact artifact = MacroExportArtifact.fromDeclaration("Producer", original);
        MacroExportArtifact malformed = replace(
                artifact, artifact.format(), artifact.semanticsAbi(),
                artifact.ownerModule(), artifact.canonicalContent() + (char) 0xD800,
                artifact.contentSha256());

        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> malformed.verifyAgainst("Producer", original));
        assertTrue(invalid.getMessage().contains("malformed UTF-8"));
    }

    @Test
    void oversizedPayloadAndInvalidOwnerFailClosed() {
        Ast.MacroDecl original = macro("return value;", true);
        MacroExportArtifact artifact = MacroExportArtifact.fromDeclaration("Producer", original);

        assertThrows(IllegalArgumentException.class,
                () -> replace(artifact, artifact.format(), artifact.semanticsAbi(),
                        artifact.ownerModule(),
                        "a".repeat(MacroCanonicalIdentity.MAX_BYTES + 1),
                        artifact.contentSha256()));

        assertThrows(IllegalArgumentException.class,
                () -> MacroExportArtifact.fromDeclaration("Producer\nForged", original));

        assertThrows(IllegalArgumentException.class,
                () -> MacroExportArtifact.fromDeclaration("Producer" + (char) 0xD800, original));

        Ast.MacroDecl forgedName = new Ast.MacroDecl(
                "identity" + (char) 0x0A + "Injected",
                original.visibility(),
                original.parameters(),
                original.resultKind(),
                original.body(),
                original.definitionSite());
        assertThrows(IllegalArgumentException.class,
                () -> MacroExportArtifact.fromDeclaration("Producer", forgedName));
    }

    @Test
    void utf8ByteBudgetIsEnforcedEvenWhenCharacterCountFits() {
        Ast.MacroDecl original = macro("return value;", true);
        MacroExportArtifact valid = MacroExportArtifact.fromDeclaration("Producer", original);
        // The envelope's cheap UTF-16 bound is not the UTF-8 wire budget.
        String wide = "é".repeat(MacroCanonicalIdentity.MAX_BYTES / 2 + 1);
        assertTrue(wide.length() <= MacroCanonicalIdentity.MAX_BYTES);
        MacroExportArtifact oversizedUtf8 = replace(
                valid, valid.format(), valid.semanticsAbi(),
                valid.ownerModule(), wide, valid.contentSha256());

        IllegalArgumentException oversized = assertThrows(
                IllegalArgumentException.class,
                () -> oversizedUtf8.verifyAgainst("Producer", original));
        assertTrue(oversized.getMessage().contains("byte budget"),
                oversized::toString);
    }

    @Test
    void digestMustBeExactlyLowercaseSha256Hex() {
        Ast.MacroDecl original = macro("return value;", true);
        MacroExportArtifact valid = MacroExportArtifact.fromDeclaration("Producer", original);
        MacroExportArtifact wrongDigestCase = replace(
                valid, valid.format(), valid.semanticsAbi(),
                valid.ownerModule(), valid.canonicalContent(),
                "A" + valid.contentSha256().substring(1));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> wrongDigestCase.verifyAgainst("Producer", original));
        assertTrue(failure.getMessage().contains("invalid SHA-256"));
    }

    @Test
    void artifactNamesMustMatchFlatSourceIdentifierGrammar() {
        Ast.MacroDecl original = macro("return value;", true);
        for (String invalidOwner : java.util.List.of(
                "../external", "Module.Other", "9Module", "Module/Root", "Module Name")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExportArtifact.fromDeclaration(invalidOwner, original),
                    invalidOwner);
        }
        for (String invalidMacroName : java.util.List.of(
                "identity/escape", "identity.method", "9identity", "identity name")) {
            Ast.MacroDecl forged = new Ast.MacroDecl(
                    invalidMacroName, original.visibility(),
                    original.parameters(), original.resultKind(),
                    original.body(), original.definitionSite());
            assertThrows(IllegalArgumentException.class,
                    () -> MacroExportArtifact.fromDeclaration("Producer", forged),
                    invalidMacroName);
        }

        Ast.MacroDecl unicode = new Ast.MacroDecl(
                "álgebra", original.visibility(), original.parameters(),
                original.resultKind(), original.body(), original.definitionSite());
        assertDoesNotThrow(() ->
                MacroExportArtifact.fromDeclaration("Prodúcer", unicode)
                        .verifyAgainst("Prodúcer", unicode));
    }
}

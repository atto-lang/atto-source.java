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
                artifact.ownerModule(), artifact.canonicalContent() + "\uD800",
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
    }
}

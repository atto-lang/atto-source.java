package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bounded, versioned build-time identity envelope for a public macro.
 *
 * <p>Important: this is not a deserializer, a macro interpreter, a checked
 * capture credential, or authorization to import or expand arbitrary external
 * compiler data. A consumer must bind an envelope to a known source declaration
 * using {@link #verifyAgainst(String, Ast.MacroDecl)} before trusting its
 * identity. Cross-unit executable macro imports remain disabled until the
 * separate typed-AST import and provenance validation gate is implemented.</p>
 */
record MacroExportArtifact(
        String format,
        String semanticsAbi,
        String ownerModule,
        String macroName,
        String canonicalContent,
        String contentSha256) {

    MacroExportArtifact {
        Objects.requireNonNull(format, "macro artifact format");
        Objects.requireNonNull(semanticsAbi, "macro semantics ABI");
        Objects.requireNonNull(ownerModule, "macro owner module");
        Objects.requireNonNull(macroName, "macro name");
        Objects.requireNonNull(canonicalContent, "canonical macro content");
        Objects.requireNonNull(contentSha256, "macro content digest");
        validateName(ownerModule, "macro owner module");
        validateName(macroName, "macro declaration name");
        if (canonicalContent.length() > MacroCanonicalIdentity.MAX_BYTES) {
            throw new IllegalArgumentException("public macro artifact exceeds bounded content bytes");
        }
    }

    static MacroExportArtifact fromDeclaration(String ownerModule, Ast.MacroDecl declaration) {
        Objects.requireNonNull(declaration, "public macro declaration");
        requirePublic(declaration);
        validateName(ownerModule, "macro owner module");
        String encoded = MacroCanonicalIdentity.encode(declaration);
        MacroExportArtifact artifact = new MacroExportArtifact(
                MacroCanonicalIdentity.FORMAT,
                MacroCanonicalIdentity.SEMANTICS_ABI,
                ownerModule,
                declaration.name(),
                encoded,
                sha256(strictUtf8(encoded)));
        artifact.verifyAgainst(ownerModule, declaration);
        return artifact;
    }

    /**
     * Rejects stale, tampered, misattributed or unsupported envelopes.
     * SHA-256 checks identity/cache consistency, not visibility or hygiene.
     * The exact compiler AST declaration is the source of semantic authority.
     */
    void verifyAgainst(String expectedOwner, Ast.MacroDecl declaration) {
        Objects.requireNonNull(declaration, "public macro declaration");
        requirePublic(declaration);
        validateName(expectedOwner, "expected macro owner module");
        if (!MacroCanonicalIdentity.FORMAT.equals(format)
                || !MacroCanonicalIdentity.SEMANTICS_ABI.equals(semanticsAbi)) {
            throw invalid("unsupported canonical format or semantics ABI");
        }
        if (!expectedOwner.equals(ownerModule) || !declaration.name().equals(macroName)) {
            throw invalid("owner or declaration identity mismatch");
        }
        byte[] supplied = strictUtf8(canonicalContent);
        if (supplied.length > MacroCanonicalIdentity.MAX_BYTES) {
            throw invalid("canonical content byte budget exceeded");
        }
        if (contentSha256.length() != 64 || !contentSha256.matches("[0-9a-f]{64}")) {
            throw invalid("invalid SHA-256 content digest");
        }
        byte[] claimed = HexFormat.of().parseHex(contentSha256);
        if (!MessageDigest.isEqual(claimed, sha256Bytes(supplied))) {
            throw invalid("content digest mismatch");
        }
        byte[] canonical = strictUtf8(MacroCanonicalIdentity.encode(declaration));
        if (!MessageDigest.isEqual(supplied, canonical)) {
            throw invalid("content does not match the bound compiler AST");
        }
    }

    private static void requirePublic(Ast.MacroDecl declaration) {
        if (declaration.visibility() != Ast.Visibility.PUBLIC) {
            throw invalid("private macros cannot be exported");
        }
    }

    private static void validateName(String value, String label) {
        if (value == null || value.isBlank()
                || value.length() > MacroSourceUnitIdentity.MAX_UNIT_ID_CHARS) {
            throw invalid("invalid " + label);
        }
        // Flat module and macro names follow the source lexer's identifier
        // alphabet: a Unicode BMP letter or underscore, then letters,
        // underscores or ASCII digits. Dotted/qualified names are not source
        // ModuleDecl or MacroDecl names; qualification occurs at lookup.
        // This also rejects path separators, whitespace and control bytes.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean letterOrUnderscore = Character.isLetter(c) || c == '_';
            if (i == 0 ? !letterOrUnderscore
                    : !(letterOrUnderscore || c >= '0' && c <= '9')) {
                throw invalid("invalid " + label + " (not a flat source identifier)");
            }
        }
        // Keep strict Unicode admission independent of AST constructor checks.
        strictUtf8(value);
    }

    private static byte[] strictUtf8(String content) {
        if (content.length() > MacroCanonicalIdentity.MAX_BYTES) {
            throw invalid("canonical content byte budget exceeded");
        }
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(content));
            if (buffer.remaining() > MacroCanonicalIdentity.MAX_BYTES) {
                throw invalid("canonical content byte budget exceeded");
            }
            byte[] encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            return encoded;
        } catch (CharacterCodingException invalid) {
            throw invalid("malformed UTF-8 canonical content");
        }
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha256Bytes(content));
    }

    private static byte[] sha256Bytes(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for macro build identity", unavailable);
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("public macro artifact rejected: " + reason
                + "; runtime fallback and unchecked macro import are forbidden");
    }
}

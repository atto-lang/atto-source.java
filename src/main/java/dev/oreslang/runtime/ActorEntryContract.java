package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Exact ABI contract for a persistent actor exported as a hot-load entry.
 *
 * <p>This verifier targets the unified actor-as-class model. It deliberately
 * ignores private helpers, fields, control flow, and object layout. Admission
 * depends only on the explicit entry identity, actor isolation domain, and the
 * one public receive-message boundary.</p>
 */
public record ActorEntryContract(
        String actorName,
        Ast.ActorKind actorKind,
        Ast.TypeRef messageType,
        boolean structuralMessage,
        boolean mutableMessage) {

    public ActorEntryContract {
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(actorKind, "actorKind");
        Objects.requireNonNull(messageType, "messageType");
        if (actorName.isBlank()) {
            throw new IllegalArgumentException("actor entry name cannot be blank");
        }
        if (actorKind == Ast.ActorKind.NONE) {
            throw new IllegalArgumentException("actor entry contract requires an actor isolation kind");
        }
    }

    public static ActorEntryContract of(
            String actorName,
            Ast.ActorKind actorKind,
            Ast.TypeRef messageType) {
        return new ActorEntryContract(actorName, actorKind, messageType, false, false);
    }

    public Verification verify(Ast.Program program, Ast.EntryExportDecl entry) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(entry, "entry");

        if (entry.kind() != Ast.ExportKind.ACTOR) {
            throw new IllegalArgumentException(
                    "actor entry contract requires 'export entry actor', found " + entry.kind());
        }
        if (!entry.name().equals(actorName)) {
            throw new IllegalArgumentException(
                    "actor entry contract names '" + actorName
                            + "' but code unit exports actor '" + entry.name() + "'");
        }

        Ast.ClassDecl actor = null;
        for (Ast.ModuleDecl module : program.modules()) {
            if (!Parser.ROOT_MODULE.equals(module.name())) continue;
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl candidate
                        && candidate.name().equals(actorName)) {
                    if (actor != null) {
                        throw new IllegalArgumentException(
                                "actor entry '" + actorName + "' is ambiguous");
                    }
                    actor = candidate;
                }
            }
        }

        if (actor == null || actor.actorKind() == Ast.ActorKind.NONE) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName + "' does not name a top-level actor class");
        }
        if (actor.actorKind() != actorKind) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName + "' isolation mismatch: expected "
                            + actorKind + ", found " + actor.actorKind());
        }

        Ast.MethodDecl receive = null;
        for (Ast.MethodDecl method : actor.methods()) {
            if (!method.name().equals("receive")
                    || method.visibility() != Ast.Visibility.PUBLIC
                    || method.isStatic()) {
                continue;
            }
            if (receive != null) {
                throw new IllegalArgumentException(
                        "actor entry '" + actorName + "' exposes more than one public receive method");
            }
            receive = method;
        }

        if (receive == null) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName + "' has no public receive(message): void boundary");
        }
        if (receive.isAbstract()
                || receive.async()
                || !receive.genericParameters().isEmpty()
                || receive.parameters().size() != 1
                || receive.returnType() == null
                || !"void".equals(receive.returnType().name())
                || !receive.returnType().arguments().isEmpty()
                || receive.returnType().inferArguments()) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName
                            + "' receive boundary must be concrete, non-generic, one-argument, and void");
        }

        Ast.Param actual = receive.parameters().getFirst();
        if (!Objects.equals(actual.type(), messageType)
                || actual.structural() != structuralMessage
                || actual.mutable() != mutableMessage) {
            throw new IllegalArgumentException(
                    "actor entry '" + actorName + "' receive ABI mismatch: expected "
                            + canonicalReceiveSignature() + ", found "
                            + render(actual));
        }

        return new Verification(actorName, actorKind, abiDigest());
    }

    public String abiDigest() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalSignature().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String canonicalSignature() {
        return "ores-persistent-actor-entry-abi-v1\n"
                + "actor=" + actorName + "\n"
                + "isolation=" + actorKind + "\n"
                + "receive=" + canonicalReceiveSignature() + "\n";
    }

    private String canonicalReceiveSignature() {
        return (structuralMessage ? "structural:" : "nominal:")
                + (mutableMessage ? "mut:" : "readonly:")
                + typeRef(messageType)
                + "->void";
    }

    private static String render(Ast.Param parameter) {
        return (parameter.structural() ? "structural:" : "nominal:")
                + (parameter.mutable() ? "mut:" : "readonly:")
                + typeRef(parameter.type())
                + "->void";
    }

    private static String typeRef(Ast.TypeRef type) {
        if (type.isStringLiteral()) return "'" + type.stringLiteralValue() + "'";
        StringBuilder out = new StringBuilder(type.name());
        if (type.inferArguments()) return out.append("<>").toString();
        if (!type.arguments().isEmpty()) {
            out.append('<');
            for (int i = 0; i < type.arguments().size(); i++) {
                if (i > 0) out.append(',');
                out.append(typeRef(type.arguments().get(i)));
            }
            out.append('>');
        }
        return out.toString();
    }

    public record Verification(
            String actorName,
            Ast.ActorKind actorKind,
            String abiDigest) { }
}

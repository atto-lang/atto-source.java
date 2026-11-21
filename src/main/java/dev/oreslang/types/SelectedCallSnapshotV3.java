package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Immutable integrity snapshot for a previously selected v3 callable.
 *
 * The signature digest MUST originate from the compiler's checked symbol/ABI
 * resolver. This record is not a signature, secret MAC, source type proof, or
 * authorization to run guest code. Its purpose is rejecting plan reuse after
 * a target, formal declaration, or lexical call-item shape changes.
 */
public final class SelectedCallSnapshotV3 {
    private SelectedCallSnapshotV3() { }

    public record Target(String kind, String owner, String name,
                         int abiVersion, String checkedSignatureDigest) {
        public Target {
            if (kind == null || kind.isBlank() || owner == null || owner.isBlank()
                    || name == null || name.isBlank() || abiVersion < 3) {
                throw new IllegalArgumentException("incomplete selected target identity");
            }
            if (checkedSignatureDigest == null
                    || !checkedSignatureDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("checked signature digest must be canonical SHA-256");
            }
        }
    }

    public static final class Receipt {
        private final String fingerprint;
        private final Target target;
        private final DefaultCallShapeV3.Plan plan;
        // Optional non-serializable AST identity: the caller must verify this
        // before using a plan for a particular source expression.
        private final Ast.Expr callSite;

        private Receipt(String fingerprint, Target target,
                        DefaultCallShapeV3.Plan plan, Ast.Expr callSite) {
            this.fingerprint = fingerprint;
            this.target = target;
            this.plan = plan;
            this.callSite = callSite;
        }
        public String fingerprint() { return fingerprint; }
        public Target target() { return target; }
        public DefaultCallShapeV3.Plan plan() { return plan; }

        /**
         * Stronger check for a specific AST occurrence, including the source
         * expressions themselves by object identity. It is NOT proof that
         * the AST or compiler's selected symbol has been type-checked.
         */
        public void verifyForCallSite(Ast.Expr expectedSite, Target expected,
                List<DefaultCallShapeV3.Formal> formals,
                List<DefaultCallShapeV3.Item> items) {
            if (expectedSite == null || callSite == null || expectedSite != callSite) {
                throw new IllegalArgumentException(
                        "call plan not bound to this exact checked AST occurrence");
            }
            verifySnapshot(expected, formals, items);
        }

        /** Shape-only verification cannot downgrade an AST-bound receipt. */
        public void verify(Target expected, List<DefaultCallShapeV3.Formal> formals,
                           List<DefaultCallShapeV3.Item> items) {
            if (callSite != null) {
                throw new IllegalArgumentException(
                        "call-site-bound receipt requires verifyForCallSite");
            }
            verifySnapshot(expected, formals, items);
        }

        private void verifySnapshot(Target expected,
                List<DefaultCallShapeV3.Formal> formals,
                List<DefaultCallShapeV3.Item> items) {
            Objects.requireNonNull(expected, "selected target");
            if (!target.equals(expected)) {
                throw new IllegalArgumentException("call plan belongs to a different declaration or ABI");
            }
            DefaultCallShapeV3.Plan rebound = DefaultCallShapeV3.bind(formals, items);
            if (!plan.equals(rebound)
                    || !fingerprint.equals(digest(expected, formals, items, rebound))) {
                throw new IllegalArgumentException("call plan no longer matches formal/source snapshot");
            }
        }
    }

    public static Receipt bind(
            Target target, List<DefaultCallShapeV3.Formal> formals,
            List<DefaultCallShapeV3.Item> items) {
        Objects.requireNonNull(target, "selected target");
        formals = List.copyOf(formals);
        items = List.copyOf(items);
        DefaultCallShapeV3.Plan plan = DefaultCallShapeV3.bind(formals, items);
        return new Receipt(digest(target, formals, items, plan), target, plan, null);
    }

    /** Bind to an actual immutable AST occurrence; do not use for unverified dispatch. */
    public static Receipt bindForCallSite(
            Target target, Ast.Expr callSite,
            List<DefaultCallShapeV3.Formal> formals,
            List<DefaultCallShapeV3.Item> items) {
        Objects.requireNonNull(target, "selected target");
        Objects.requireNonNull(callSite, "AST call occurrence");
        formals = List.copyOf(formals);
        items = List.copyOf(items);
        DefaultCallShapeV3.Plan plan = DefaultCallShapeV3.bind(formals, items);
        return new Receipt(digest(target, formals, items, plan), target, plan, callSite);
    }

    private static String digest(
            Target target, List<DefaultCallShapeV3.Formal> formals,
            List<DefaultCallShapeV3.Item> items, DefaultCallShapeV3.Plan plan) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x4f524533); // "ORE3" format separation
            write(out, target.kind());
            write(out, target.owner());
            write(out, target.name());
            out.writeInt(target.abiVersion());
            write(out, target.checkedSignatureDigest());
            out.writeInt(formals.size());
            for (DefaultCallShapeV3.Formal formal : formals) {
                write(out, formal.name());
                out.writeBoolean(formal.intrinsicOption());
                out.writeBoolean(formal.markedOmittable());
                DefaultCallShapeV3.Constant constant = formal.defaultConstant();
                out.writeBoolean(constant != null);
                if (constant != null) {
                    write(out, constant.kind().name());
                    if (constant.kind() != DefaultCallShapeV3.ConstantKind.NONE)
                        write(out, constant.value().toString());
                }
            }
            out.writeInt(items.size());
            for (DefaultCallShapeV3.Item item : items) {
                out.writeBoolean(item.label() != null);
                if (item.label() != null) write(out, item.label());
                out.writeBoolean(item.omission());
            }
            out.writeInt(plan.abiSlots().size());
            for (DefaultCallShapeV3.Bound bound : plan.abiSlots()) {
                out.writeInt(bound.formalIndex());
                out.writeInt(bound.rawItemIndex());
                out.writeInt(bound.evaluatedIndex());
                write(out, bound.origin().name());
                write(out, bound.fill().name());
            }
            out.writeInt(plan.evaluatedRawIndices().size());
            for (int sourceIndex : plan.evaluatedRawIndices()) out.writeInt(sourceIndex);
            out.flush();
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot compute checked-call snapshot digest", e);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        // String#getBytes uses replacement for malformed UTF-16 and is NOT
        // collision-free. Reject unpaired surrogates instead of hashing two
        // different Java Strings to the same UTF-8 replacement bytes.
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "checked-call digest contains malformed Unicode", e);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}

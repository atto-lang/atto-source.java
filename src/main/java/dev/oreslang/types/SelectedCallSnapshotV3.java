package dev.oreslang.types;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

        private Receipt(String fingerprint, Target target, DefaultCallShapeV3.Plan plan) {
            this.fingerprint = fingerprint;
            this.target = target;
            this.plan = plan;
        }
        public String fingerprint() { return fingerprint; }
        public Target target() { return target; }
        public DefaultCallShapeV3.Plan plan() { return plan; }

        public void verify(Target expected, List<DefaultCallShapeV3.Formal> formals,
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
        return new Receipt(digest(target, formals, items, plan), target, plan);
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
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}

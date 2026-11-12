package dev.oreslang.types;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure, compile-time receiver namespace admission guard. This is diagnostic
 * metadata only: successful validation never grants actor runtime authority.
 * The owner index must be built from the already authenticated package source
 * set and must include all local nominal type kinds, not just actors.
 */
public final class ReceiverNamespaceGate {
    private ReceiverNamespaceGate() { }

    public static void requireLocalOwner(
            String declaringNamespace, String receiverType, String methodName,
            Map<String, ? extends Set<String>> namespacesByNominal) {
        if (declaringNamespace == null || declaringNamespace.isBlank()
                || receiverType == null || receiverType.isBlank()
                || methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("invalid out-of-line receiver declaration");
        }
        Objects.requireNonNull(namespacesByNominal, "nominal owner index");
        Set<String> owners = namespacesByNominal.get(receiverType);
        if (owners == null || owners.isEmpty() || owners.contains(declaringNamespace)) {
            // Unknown receivers and locally shadowed non-actor types are checked
            // independently by the nominal kind/visibility binder. Never infer
            // an actor from an equal spelling in some other namespace.
            return;
        }
        // Sorting gives input-order-independent diagnostics even when the
        // source graph or alias declaration order changes.
        TreeSet<String> ordered = new TreeSet<>(owners);
        throw new IllegalArgumentException(
                "EXTERNAL_RECEIVER_NAMESPACE_MISMATCH: mtd '" + methodName
                        + "' in namespace '" + declaringNamespace
                        + "' cannot attach to receiver '" + receiverType
                        + "' declared in " + ordered
                        + "; declare the method in the receiver's own namespace");
    }
}

package dev.oreslang.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * File/namespace completion ledger for authenticated, typed source facts.
 * This is not a parser, package authenticator, layout checker, or guest compiler.
 * The caller must supply facts from the same pinned package source snapshot.
 * No class is completed on the basis of a text match or an imported alias.
 */
public final class NamespaceClassCompletionV2 {
    private static final int MAX_FACTS = 4096;
    public record OwnerKey(String packageIdentity, NamespaceScopeV2.Scope scope,
                           String modulePath, String name) {
        public OwnerKey {
            Objects.requireNonNull(packageIdentity);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(modulePath);
            Objects.requireNonNull(name);
            if (packageIdentity.isEmpty() || name.isEmpty()) throw invalid("E_NAMESPACE");
        }
    }
    public enum Form { DECLARED, INLINE }
    public enum SlotKind { CONSTRUCTOR, METHOD }
    public record Owner(OwnerKey key, Form form) {
        public Owner { Objects.requireNonNull(key); Objects.requireNonNull(form); }
    }
    /** Slot identity is supplied by the typed front end, not by spelling alone. */
    public record Slot(SlotKind kind, String signature) {
        public Slot {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(signature);
            if (signature.isEmpty()) throw invalid("E_SYNTAX");
        }
    }
    public record Attachment(OwnerKey contributor, OwnerKey owner, Slot slot) {
        public Attachment {
            Objects.requireNonNull(contributor); Objects.requireNonNull(owner); Objects.requireNonNull(slot);
        }
    }
    public record Completed(Owner owner, List<Slot> slots) {
        public Completed { slots = List.copyOf(slots); }
    }
    private NamespaceClassCompletionV2() {}

    public static Map<OwnerKey, Completed> close(List<Owner> owners, List<Attachment> attachments) {
        if (owners.size() > MAX_FACTS || attachments.size() > MAX_FACTS
                || (long) owners.size() + attachments.size() > MAX_FACTS) throw invalid("E_SOURCE_LIMIT");
        Map<OwnerKey, Owner> declared = new HashMap<>();
        Map<OwnerKey, Map<Slot, Slot>> slots = new HashMap<>();
        for (Owner owner : owners) {
            if (declared.putIfAbsent(owner.key(), owner) != null) throw invalid("E_DUPLICATE");
            slots.put(owner.key(), new HashMap<>());
        }
        for (Attachment a : attachments) {
            OwnerKey c = a.contributor(), target = a.owner();
            // The contributor scope and owning module are checked independently
            // of the resolved nominal target. Visibility does not grant attachment.
            if (!c.packageIdentity().equals(target.packageIdentity()) ||
                    !c.scope().equals(target.scope()) ||
                    !c.modulePath().equals(target.modulePath())) throw invalid("E_NAMESPACE");
            Owner owner = declared.get(target);
            if (owner == null) throw invalid("E_UNBOUND");
            if (owner.form() != Form.DECLARED) throw invalid("E_UNSUPPORTED");
            if (slots.get(target).putIfAbsent(a.slot(), a.slot()) != null) throw invalid("E_DUPLICATE");
        }
        Map<OwnerKey, Completed> completed = new HashMap<>();
        for (Owner owner : owners) {
            List<Slot> ordered = new ArrayList<>(slots.get(owner.key()).values());
            if (owner.form() == Form.DECLARED && ordered.isEmpty()) throw invalid("E_UNLINKED");
            ordered.sort(java.util.Comparator.comparing((Slot s) -> s.kind().name()).thenComparing(Slot::signature));
            completed.put(owner.key(), new Completed(owner, ordered));
        }
        return Map.copyOf(completed);
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code + ": invalid namespace completion facts");
    }
}

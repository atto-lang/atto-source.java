package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.NominalEnumOverloads.PairStatus;
import dev.oreslang.types.NominalEnumOverloads.Position;
import dev.oreslang.types.NominalEnumOverloads.Signature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;

/**
 * Static, local-class proof substrate for the future nominal-enum method ABI.
 *
 * <p>IMPORTANT: This class does NOT enable same-arity enum methods in the
 * TypeChecker or runtime. Today's CallableSelector and vtables remain
 * arity-only and MUST continue rejecting same-arity declarations. A future
 * compiler pass can publish these slots only after all inherited signatures,
 * override obligations, ownership effects and executable call bindings agree.
 * This helper never inspects runtime argument values or enum case payloads.
 */
public final class NominalMethodSlots {
    private NominalMethodSlots() { }

    public static final int MAX_LOCAL_METHOD_DECLARATIONS = 4096;
    public static final int MAX_METHOD_NAME_BYTES = 256;
    public static final int MAX_METHOD_OWNER_BYTES = 1024;

    /** Dispatch domains are intentionally separate even for the same name. */
    public enum Domain { INSTANCE, STATIC }

    /** An override slot has no declaring-class ID: an override retains the
     * original signature. Link symbols additionally qualify the owner. */
    public record Slot(Domain domain, String name, List<Position> positions) {
        public Slot {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(name, "method name");
            Objects.requireNonNull(positions, "method positions");
            if (name.isBlank()) throw new IllegalArgumentException("blank method name");
            requireValidUtf16(name);
            if (name.getBytes(StandardCharsets.UTF_8).length > MAX_METHOD_NAME_BYTES) {
                throw new IllegalArgumentException("nominal method name size bound exceeded");
            }
            positions = List.copyOf(positions);
            if (positions.size() > NominalEnumOverloads.MAX_OVERLOAD_ARITY)
                throw new IllegalArgumentException("nominal method slot arity bound exceeded");
        }

        public int arity() { return positions.size(); }

        /** Injective, owner-qualified symbol. The owner is a resolved
         * module/class declaration identity, never a runtime class name. */
        public String linkSymbol(String ownerId) {
            Objects.requireNonNull(ownerId, "owner ID");
            if (ownerId.isBlank()) throw new IllegalArgumentException("blank method owner ID");
            requireValidUtf16(ownerId);
            if (ownerId.getBytes(StandardCharsets.UTF_8).length > MAX_METHOD_OWNER_BYTES) {
                throw new IllegalArgumentException("nominal method owner size bound exceeded");
            }
            StringBuilder out = new StringBuilder("$methodoverload$v1$");
            append(out, ownerId);
            append(out, domain.name());
            append(out, name);
            out.append(arity()).append('$');
            for (Position p : positions) {
                append(out, p.kind().name());
                append(out, p.id());
            }
            return out.toString();
        }

        private static void append(StringBuilder out, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.append(bytes.length).append('_')
                    .append(HexFormat.of().formatHex(bytes)).append('$');
        }
    }

    private record FamilyKey(Domain domain, String name, int arity) { }

    /**
     * Admit only *local* same-arity enum method families as proof objects.
     * Arity-distinct methods are handled by the existing CallableSelector;
     * no executable vtable is created here. A class with only one method for
     * a name/arity contributes no typed slot.
     *
     * <p>Inherited method merging is deliberately NOT implemented here:
     * the compiler must not use this result to bypass its current rejection
     * until it proves all ancestor/diamond and virtual override contracts.
     */
    public static Map<Slot, Ast.MethodDecl> proveLocalSameArityFamilies(
            Ast.Program program, Ast.ClassDecl owner) {
        Objects.requireNonNull(program, "compiler program");
        Objects.requireNonNull(owner, "declaring class");
        // The helper must resolve enum identities itself from the same
        // checked program. Accepting caller-made name->enum maps would let a
        // forged ID bypass lexical namespace and declaration provenance.
        // Do not trust a same-looking class AST from another code image.
        int ownerMatches = 0;
        String resolvedOwner = null;
        java.util.Set<String> classIds = new java.util.HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ClassDecl candidate)) continue;
                String key = module.name() + "::" + candidate.name();
                if (!classIds.add(key)) {
                    throw new IllegalArgumentException(
                            "duplicate nominal method owner declaration: " + key);
                }
                if (candidate == owner) {
                    ownerMatches++;
                    resolvedOwner = key;
                }
            }
        }
        if (ownerMatches != 1) {
            throw new IllegalArgumentException(
                    "nominal method owner is not a unique declaration in compiler program");
        }
        Map<String, String> eligibleEnumSymbols =
                NominalEnumOverloads.eligibleEnumSymbols(program);
        if (owner.methods().size() > MAX_LOCAL_METHOD_DECLARATIONS) {
            throw new IllegalArgumentException("nominal class method declaration limit exceeded");
        }
        Map<FamilyKey, List<Ast.MethodDecl>> families = new LinkedHashMap<>();
        for (Ast.MethodDecl method : owner.methods()) {
            FamilyKey key = new FamilyKey(method.isStatic() ? Domain.STATIC : Domain.INSTANCE,
                    method.name(), method.arity());
            List<Ast.MethodDecl> family =
                    families.computeIfAbsent(key, ignored -> new ArrayList<>());
            // Enforce before a pathological family grows without bounds.
            if (family.size() >= NominalEnumOverloads.MAX_SAME_ARITY_CANDIDATES) {
                throw new IllegalArgumentException(
                        "nominal method family candidate bound exceeded");
            }
            family.add(method);
        }

        Map<Slot, Ast.MethodDecl> accepted = new HashMap<>();
        for (Map.Entry<FamilyKey, List<Ast.MethodDecl>> entry : families.entrySet()) {
            List<Ast.MethodDecl> candidates = entry.getValue();
            if (candidates.size() < 2) continue;
            if (candidates.size() > NominalEnumOverloads.MAX_SAME_ARITY_CANDIDATES) {
                throw new IllegalArgumentException("nominal method family candidate bound exceeded");
            }
            if (!owner.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "generic owner requires substitution before nominal method slot proof");
            }
            // Actor messages require an independent, sealed message ABI and
            // single-flight ownership proof. Method overloading must never
            // reclassify actor entrypoints as ordinary vtable members.
            if (owner.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "actor entrypoints cannot enter nominal method slot proof");
            }
            List<Signature> signatures = new ArrayList<>(candidates.size());
            for (Ast.MethodDecl method : candidates) {
                if (!method.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "generic method cannot enter nominal same-arity slot proof");
                }
                signatures.add(NominalEnumOverloads.signature(
                        method.name(), method.parameters(), eligibleEnumSymbols));
            }
            // Pairwise disjointness, not source order, return types, effect
            // modifiers, primitive widening, or structural compatibility.
            for (int i = 0; i < signatures.size(); i++) {
                for (int j = i + 1; j < signatures.size(); j++) {
                    if (NominalEnumOverloads.compare(signatures.get(i), signatures.get(j))
                            != PairStatus.DISTINCT_ENUMS) {
                        throw new IllegalArgumentException(
                                "same-arity method family lacks pairwise distinct nominal enums: "
                                        + resolvedOwner + "." + entry.getKey().name()
                                        + "/" + entry.getKey().arity());
                    }
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                Slot slot = new Slot(entry.getKey().domain(), entry.getKey().name(),
                        signatures.get(i).positions());
                if (accepted.putIfAbsent(slot, candidates.get(i)) != null) {
                    throw new IllegalArgumentException("duplicate nominal method slot: " + slot);
                }
            }
        }
        return Map.copyOf(accepted);
    }

    private static void requireValidUtf16(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("invalid UTF-16 method slot identity");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid UTF-16 method slot identity");
            }
        }
    }
}

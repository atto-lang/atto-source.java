package dev.oreslang.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A nullary, closed nominal enum case is an immutable actor-safe Symbol value.
 *
 * <p>Identity is (defining source unit, nominal enum name, closed case schema,
 * case name), not an ordinal, heap pointer, interning slot, or actor generation.
 * The defining unit is compiler-resolved; this host type is data, not an
 * authentication or unforgeable-capability mechanism. Payload-bearing ADT cases
 * are deliberately not represented by this scalar type.
 *
 * <p>There is no global interner. Same-process actor mail can carry this value
 * directly across private arenas; the containing reference graph has no
 * mutable actor-owned state. Other processes and isolated memories still need
 * an explicit, authenticated transport encoding and schema check.
 */
public final class OresEnumSymbol {
    private static final int MAX_CASES = 4096;

    /** Immutable, compiler-provided closed declaration image. */
    public static final class Declaration {
        private final String sourceUnitId;
        private final String enumName;
        private final Set<String> cases;
        // Charged once per mailbox message, computed once per declaration.
        // A large closed case schema must not become an unaccounted pointer graph.
        private final long transportLogicalBytes;

        private Declaration(String sourceUnitId, String enumName, List<String> caseNames) {
            this.sourceUnitId = OresSymbol.normalizeAndValidate(sourceUnitId);
            this.enumName = validQualifiedName(enumName, "enum");
            Objects.requireNonNull(caseNames, "caseNames");
            if (caseNames.isEmpty() || caseNames.size() > MAX_CASES) {
                throw new IllegalArgumentException("enum requires 1..4096 nullary cases");
            }
            LinkedHashSet<String> checked = new LinkedHashSet<>();
            for (String name : caseNames) {
                String variant = validIdentifier(name, "enum case");
                if (!checked.add(variant)) {
                    throw new IllegalArgumentException("duplicate enum case " + variant);
                }
            }
            this.cases = Set.copyOf(checked);
            long bytes = Math.addExact(
                    96L, 2L * (this.sourceUnitId.length() + this.enumName.length()));
            for (String caseName : checked) {
                bytes = Math.addExact(bytes, 24L + 2L * caseName.length());
            }
            this.transportLogicalBytes = bytes;
        }

        public String sourceUnitId() { return sourceUnitId; }
        public String enumName() { return enumName; }
        public Set<String> cases() { return cases; }
        long transportLogicalBytes() { return transportLogicalBytes; }

        /** Resolves a declared case; arbitrary strings cannot become enum values. */
        public OresEnumSymbol caseNamed(String caseName) {
            String name = validIdentifier(caseName, "enum case");
            if (!cases.contains(name)) {
                throw new IllegalArgumentException("unknown enum case " + enumName + "." + name);
            }
            return new OresEnumSymbol(this, name);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Declaration declaration
                    && sourceUnitId.equals(declaration.sourceUnitId)
                    && enumName.equals(declaration.enumName)
                    && cases.equals(declaration.cases);
        }

        @Override public int hashCode() {
            return Objects.hash(sourceUnitId, enumName, cases);
        }
    }

    private final Declaration declaration;
    private final String caseName;

    private OresEnumSymbol(Declaration declaration, String caseName) {
        this.declaration = declaration;
        this.caseName = caseName;
    }

    /** Compiler/runtime bridge; callers must supply compiler-checked metadata. */
    public static Declaration declare(
            String definingSourceUnitId, String nominalEnumName, List<String> nullaryCases) {
        return new Declaration(definingSourceUnitId, nominalEnumName, nullaryCases);
    }

    public Declaration declaration() { return declaration; }
    public String caseName() { return caseName; }

    private static String validQualifiedName(String value, String role) {
        Objects.requireNonNull(value, role);
        String canonical = OresSymbol.normalizeAndValidate(value);
        for (String part : canonical.split("\\.", -1)) validIdentifier(part, role);
        return canonical;
    }

    private static String validIdentifier(String value, String role) {
        Objects.requireNonNull(value, role);
        String canonical = OresSymbol.normalizeAndValidate(value);
        if (!canonical.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(role + " must be a canonical identifier");
        }
        return canonical;
    }

    @Override public boolean equals(Object other) {
        return other instanceof OresEnumSymbol symbol
                && declaration.equals(symbol.declaration)
                && caseName.equals(symbol.caseName);
    }

    @Override public int hashCode() {
        return Objects.hash(declaration, caseName);
    }

    @Override public String toString() {
        return declaration.enumName() + "." + caseName;
    }
}

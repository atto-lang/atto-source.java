package dev.oreslang.compiler;

/**
 * Trusted compiler provenance for a failed source unit. Do not infer the
 * originating file from user-controlled diagnostic message text.
 */
public final class SourceUnitDiagnosticException extends IllegalArgumentException {
    private final String unitId;
    private final String moduleName;
    private final String namespaceName;
    private final String detail;

    public SourceUnitDiagnosticException(
            String unitId, String moduleName, String namespaceName,
            IllegalArgumentException cause) {
        super(renderMessage(unitId, moduleName, namespaceName, cause), cause);
        this.unitId = java.util.Objects.requireNonNull(unitId);
        this.moduleName = moduleName == null ? "" : moduleName;
        this.namespaceName = namespaceName == null ? "" : namespaceName;
        this.detail = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    public String unitId() { return unitId; }
    public String moduleName() { return moduleName; }
    public String namespaceName() { return namespaceName; }
    public String detail() { return detail; }

    private static String renderMessage(
            String unitId, String moduleName, String namespaceName,
            IllegalArgumentException cause) {
        StringBuilder message = new StringBuilder("Oreslang source unit '")
                .append(unitId).append("':");
        if (moduleName != null && !moduleName.isBlank())
            message.append(" [module: ").append(moduleName).append(']');
        if (namespaceName != null && !namespaceName.isBlank())
            message.append(" [namespace: ").append(namespaceName).append(']');
        return message.append(' ').append(
                cause.getMessage() == null || cause.getMessage().isBlank()
                        ? cause.getClass().getSimpleName() : cause.getMessage()).toString();
    }
}

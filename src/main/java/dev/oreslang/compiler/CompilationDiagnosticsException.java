package dev.oreslang.compiler;

import java.util.List;

/**
 * Compilation rejected after compiler-owned parser recovery. Each primary
 * diagnostic came from a real lexer/parser token, not stderr heuristics.
 * Recovery is diagnostic-only: no partial AST is approved for execution.
 */
public final class CompilationDiagnosticsException extends IllegalArgumentException {
    private final List<DiagnosticCollector.Diagnostic> diagnostics;
    private final boolean mayHaveMore;

    public CompilationDiagnosticsException(DiagnosticCollector collector) {
        super(collector.diagnostics().size() + " compiler error(s) reported"
                + (collector.mayHaveMore() ? "; additional errors may exist" : ""));
        if (collector.diagnostics().isEmpty())
            throw new IllegalArgumentException("cannot reject a clean compilation");
        this.diagnostics = collector.diagnostics();
        this.mayHaveMore = collector.mayHaveMore();
    }

    public List<DiagnosticCollector.Diagnostic> diagnostics() { return diagnostics; }
    public boolean mayHaveMore() { return mayHaveMore; }
}

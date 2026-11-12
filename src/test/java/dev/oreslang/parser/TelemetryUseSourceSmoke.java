package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.TaskCaptureNames;
import dev.oreslang.imports.ImportRules;
import java.util.List;
import java.util.Set;

/**
 * Standalone, source-executed parser/import/capture smoke. This does not
 * authorize scoped telemetry execution, OTLP, or real guest await.
 */
public final class TelemetryUseSourceSmoke {
    private static int assertions;

    private static void check(boolean ok, String explanation) {
        if (!ok) throw new AssertionError(explanation);
        assertions++;
    }

    private static Ast.FunctionDecl function(String source) {
        Ast.Program parsed = Parser.parse("fnc run(): void {\n" + source + "\nreturn;\n}\n");
        return (Ast.FunctionDecl) parsed.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl)
                .findFirst().orElseThrow();
    }

    private static void parseDenied(String source) {
        boolean denied = false;
        try {
            Parser.parse(source);
        } catch (IllegalArgumentException expected) {
            denied = true;
        }
        check(denied, "unexpected parser admission: " + source);
    }

    private static void importDenied(Ast.ImportDecl item) {
        boolean denied = false;
        try {
            ImportRules.validate(item);
        } catch (IllegalArgumentException expected) {
            denied = expected.getMessage().contains("trusted compiler builtin resolver");
        }
        check(denied, "untrusted std.telemetry import must fail closed");
    }

    public static void main(String[] ignored) {
        Ast.Program program = Parser.parse(
                "use std.telemetry as trace;\nfnc main(): void { return; }\n");
        check(program.imports().size() == 1, "one canonical import");
        Ast.ImportDecl imported = program.imports().getFirst();
        check(imported.kind() == Ast.ImportKind.MODULE, "typed module import");
        check(imported.path().equals(ImportRules.TELEMETRY_USE_SYNTAX_PATH),
                "canonical syntax provenance path");
        check(imported.names().equals(List.of("telemetry")), "canonical source name");
        check(imported.namespace().equals("trace"), "explicit namespace alias");
        check(ImportRules.exposedBindings(imported).equals(List.of("trace")),
                "exposed binding honors alias");
        importDenied(imported);
        // Canonical builtin authority must NOT be obtained by generic quoted imports.
        Ast.ImportDecl forged = Parser.parse(
                "import module telemetry from \"std.telemetry\";\nfnc f(): void { return; }")
                .imports().getFirst();
        importDenied(forged);
        parseDenied("import module telemetry from \"@builtin:std.telemetry\";\nfnc f(): void { return; }");
        parseDenied("use std.fs as fs;\nfnc f(): void { return; }");
        parseDenied("use my.telemetry as trace;\nfnc f(): void { return; }");
        parseDenied("use std.telemetry as _;\nfnc f(): void { return; }");
        parseDenied("use std.telemetry as trace\nfnc f(): void { return; }");
        parseDenied("use std.telemetry as trace.extra;\nfnc f(): void { return; }");
        parseDenied("use std.telemetry as trace;\nuse std.tracing as t;\nfnc f(): void { return; }");
        Ast.Program legacy = Parser.parse(
                "import module Thing from \"thing.atto\";\nfnc f(): void { return; }");
        check(legacy.imports().size() == 1, "legacy import still parses");
        ImportRules.validate(legacy.imports().getFirst());
        check(true, "legacy import path still valid");

        Ast.FunctionDecl fn = function("""
                using telemetry.span(label) as span {
                    span.event(payload);
                    const local = span;
                }
                """);
        Ast.UsingTelemetrySpanStmt scope =
                (Ast.UsingTelemetrySpanStmt) fn.body().getFirst();
        check(scope.binding().equals("span"), "span alias in AST");
        check(scope.body().size() == 2, "scope body retains statements");
        Set<String> names = TaskCaptureNames.referencedIn(fn.body());
        check(names.containsAll(Set.of("telemetry", "label", "payload")),
                "inventory covers resource and body references");
        check(!names.contains("local"), "local slot cannot escape capture inventory");
        check(!names.contains("span"), "span alias not captured from parent");
        Ast.FunctionDecl shadow = function("""
                using telemetry.span(span) as span {
                    span.event(payload);
                }
                """);
        check(TaskCaptureNames.referencedIn(shadow.body()).contains("span"),
                "initializer's same-named outer span remains captured");
        Ast.FunctionDecl borrowed = function("""
                using request.borrow() as view {
                    view.path();
                }
                """);
        Set<String> loanNames = TaskCaptureNames.referencedIn(borrowed.body());
        check(loanNames.contains("request"), "loan resource is inventoried");
        check(!loanNames.contains("view"), "lexical borrow alias is not captured");
        check(function("using(1);").body().getFirst() instanceof Ast.ExprStmt,
                "ordinary function call using retains expression meaning");
        check(function("using arbitrary.span(\"forged\") as spoof {}")
                        .body().getFirst() instanceof Ast.UsingTelemetrySpanStmt,
                "parser alone never grants trusted builtin authority");
        check(assertions == 27, "smoke expectation must be updated with assertions");
        // The self-accounting assertion above is also an executed check.
        System.out.println("ATTO_TRACE_USE_IMPORT_CAPTURE_PASS assertions=" + assertions);
    }
}

package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.OresSymbol;

import java.util.List;

/**
 * Canonical, bounded compiler encoding for public macro content identity.
 *
 * <p>This format participates in incremental invalidation only. Its digest is
 * never identifier/capture authority. The encoding deliberately does not use
 * record/Object.toString(), reflection, source text, host locale, or JVM object
 * identity.</p>
 */
final class MacroCanonicalIdentity {
    static final String FORMAT = "ores-macro-content-v1";
    static final String SEMANTICS_ABI = "ores-macro-semantics-v1";
    static final int MAX_NODES = 4_096;
    static final int MAX_DEPTH = 128;
    static final int MAX_CHARS = 1_048_576;

    private final StringBuilder out = new StringBuilder();
    private int nodes;

    private MacroCanonicalIdentity() {
        token("format", FORMAT);
        token("semantics-abi", SEMANTICS_ABI);
        token("const-profile", "const-v1");
        integer(CompileTimeConstEvaluator.MAX_STEPS);
        integer(CompileTimeConstEvaluator.MAX_DEPTH);
        integer(CompileTimeConstEvaluator.MAX_AGGREGATE_ELEMENTS);
        integer(CompileTimeConstEvaluator.MAX_STRING_CHARS);
        longInteger(CompileTimeConstEvaluator.MAX_ESTIMATED_BYTES);
        integer(CompileTimeConstEvaluator.MAX_CONST_TYPE_NODES);
        integer(CompileTimeConstEvaluator.MAX_CONST_TYPE_DEPTH);
        integer(MacroExpander.MAX_EXPANSIONS);
        integer(MacroExpander.MAX_EXPANSION_DEPTH);
        integer(MacroExpander.MAX_TEMPLATE_NODES);
        integer(MacroExpander.MAX_TEMPLATE_DEPTH);
        integer(MacroSyntaxProvenance.MAX_PROVENANCE_NODES);
        integer(MacroSyntaxProvenance.MAX_PROVENANCE_DEPTH);
    }

    static String encode(Ast.MacroDecl macro) {
        java.util.Objects.requireNonNull(macro, "macro declaration");
        MacroCanonicalIdentity encoder = new MacroCanonicalIdentity();
        encoder.token("name", macro.name());
        encoder.token("visibility", macro.visibility().name());
        encoder.tag("params");
        encoder.integer(macro.parameters().size());
        for (Ast.MacroParam parameter : macro.parameters()) {
            encoder.token("param-name", parameter.name());
            encoder.macroParamType(parameter.type(), 1);
        }
        encoder.tag("result");
        encoder.token("syntax-kind", macro.resultKind().syntaxKind().name());
        encoder.bool(macro.resultKind().list());
        encoder.tag("body");
        encoder.integer(macro.body().size());
        for (Ast.Stmt statement : macro.body()) {
            encoder.statement(statement, 1);
        }
        return encoder.out.toString();
    }

    private void statement(Ast.Stmt statement, int depth) {
        node(depth);
        if (statement instanceof Ast.ReturnStmt returned) {
            tag("return");
            bool(returned.value() != null);
            if (returned.value() != null) expression(returned.value(), depth + 1);
            return;
        }
        throw unsupported("macro statement " + statement.getClass().getSimpleName());
    }

    private void expression(Ast.Expr expression, int depth) {
        node(depth);
        if (expression instanceof Ast.LiteralExpr literal) {
            tag("literal");
            literal(literal.value());
            return;
        }
        if (expression instanceof Ast.NameExpr name) {
            token("name-expr", name.name());
            return;
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            token("unary", unary.operator());
            expression(unary.operand(), depth + 1);
            return;
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            token("binary", binary.operator());
            expression(binary.left(), depth + 1);
            expression(binary.right(), depth + 1);
            return;
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            tag("conditional");
            expression(conditional.condition(), depth + 1);
            expression(conditional.whenTrue(), depth + 1);
            expression(conditional.whenFalse(), depth + 1);
            return;
        }
        if (expression instanceof Ast.ListExpr list) {
            tag("list");
            integer(list.elements().size());
            for (Ast.Expr element : list.elements()) {
                expression(element, depth + 1);
            }
            return;
        }
        if (expression instanceof Ast.TupleExpr tuple) {
            tag("tuple");
            integer(tuple.elements().size());
            for (Ast.Expr element : tuple.elements()) {
                expression(element, depth + 1);
            }
            return;
        }
        if (expression instanceof Ast.ObjectExpr object) {
            token("object-kind", object.kind().name());
            integer(object.fields().size());
            for (Ast.ObjectField field : object.fields()) {
                token("field", field.name());
                expression(field.value(), depth + 1);
            }
            integer(object.declaredFields().size());
            for (Ast.StructFieldSpec field : object.declaredFields()) {
                token("declared-field", field.name());
                type(field.type(), depth + 1);
            }
            return;
        }
        if (expression instanceof Ast.CallExpr call) {
            token("call-origin", call.origin().name());
            bool(call.typeArgumentsPresent());
            integer(call.typeArguments().size());
            for (Ast.TypeRef argument : call.typeArguments()) {
                type(argument, depth + 1);
            }
            expression(call.callee(), depth + 1);
            integer(call.arguments().size());
            for (Ast.Expr argument : call.arguments()) {
                expression(argument, depth + 1);
            }
            return;
        }
        throw unsupported("macro expression " + expression.getClass().getSimpleName());
    }

    private void literal(Object value) {
        if (value instanceof Long integer) {
            token("literal-kind", "i64");
            token("i64", Long.toString(integer));
            return;
        }
        if (value instanceof Double floating) {
            token("literal-kind", "f64");
            token("f64-bits",
                    Long.toUnsignedString(Double.doubleToRawLongBits(floating), 16));
            return;
        }
        if (value instanceof Boolean bool) {
            token("literal-kind", "bool");
            bool(bool);
            return;
        }
        if (value instanceof String string) {
            token("literal-kind", "string");
            token("string", string);
            return;
        }
        if (value instanceof OresSymbol symbol) {
            token("literal-kind", "symbol");
            token("symbol", symbol.name());
            return;
        }
        throw unsupported(
                "macro literal carrier "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private void macroParamType(Ast.MacroParamType type, int depth) {
        node(depth);
        token("param-kind", type.kind().name());
        switch (type.kind()) {
            case TYPE, IDENTIFIER -> { }
            case CONST -> type(type.constType(), depth + 1);
            case SYNTAX -> token("param-syntax-kind", type.syntaxKind().name());
        }
    }

    private void type(Ast.TypeRef type, int depth) {
        node(depth);
        java.util.Objects.requireNonNull(type, "canonical macro type");
        token("type-name", type.name());
        bool(type.inferArguments());
        integer(type.arguments().size());
        for (Ast.TypeRef argument : type.arguments()) {
            type(argument, depth + 1);
        }
    }

    private void node(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "canonical macro content depth exceeds " + MAX_DEPTH);
        }
        if (++nodes > MAX_NODES) {
            throw new IllegalArgumentException(
                    "canonical macro content node budget exceeds " + MAX_NODES);
        }
    }

    private void tag(String tag) {
        token("tag", tag);
    }

    private void bool(boolean value) {
        appendBounded(value ? "1;" : "0;");
    }

    private void integer(int value) {
        appendBounded(Integer.toString(value));
        appendBounded(";");
    }

    private void longInteger(long value) {
        appendBounded(Long.toString(value));
        appendBounded(";");
    }

    /** Length-prefix every free-form string so concatenation is unambiguous. */
    private void token(String label, String value) {
        java.util.Objects.requireNonNull(label, "canonical label");
        java.util.Objects.requireNonNull(value, "canonical value");
        long required = (long) decimalDigits(label.length()) + 1L + label.length()
                + 1L + decimalDigits(value.length()) + 1L + value.length() + 1L;
        if ((long) out.length() + required > MAX_CHARS) {
            throw new IllegalArgumentException(
                    "canonical macro content encoding exceeds "
                            + MAX_CHARS + " characters");
        }
        out.append(label.length()).append(':').append(label)
                .append('=')
                .append(value.length()).append(':').append(value)
                .append(';');
    }

    private void appendBounded(String text) {
        if ((long) out.length() + text.length() > MAX_CHARS) {
            throw new IllegalArgumentException(
                    "canonical macro content encoding exceeds "
                            + MAX_CHARS + " characters");
        }
        out.append(text);
    }

    private static int decimalDigits(int value) {
        if (value < 10) return 1;
        if (value < 100) return 2;
        if (value < 1_000) return 3;
        if (value < 10_000) return 4;
        if (value < 100_000) return 5;
        if (value < 1_000_000) return 6;
        if (value < 10_000_000) return 7;
        if (value < 100_000_000) return 8;
        if (value < 1_000_000_000) return 9;
        return 10;
    }

    private IllegalArgumentException unsupported(String detail) {
        return new IllegalArgumentException(
                "public macro content cannot be canonically encoded on this compiler head: "
                        + detail
                        + "; incremental compile-time ABI fails closed");
    }
}

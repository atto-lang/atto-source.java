package dev.oreslang.ast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Experimental scalar-only outbound projection. Deliberately not a wire
 * transport or dynamic reflection facility. Expansion produces ordinary
 * AST nodes which must pass the regular type and ownership checker.
 */
final class ScalarJsonDeriver {
    private static final String ANNOTATION = "ToJson";
    private static final String GENERATED = "$generated.toJson.scalar.v1";
    private static final int MAX_FIELDS = 32;
    // A deliberately narrow, unambiguous stable wire-key grammar. String
    // values and arbitrary Unicode keys need a separately reviewed escaper.
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    private ScalarJsonDeriver() {}

    private static IllegalArgumentException deny(String message) {
        return new IllegalArgumentException("E_TO_JSON: " + message);
    }

    static void rejectOutsideField(List<Ast.Annotation> annotations) {
        if (annotations.stream().anyMatch(a -> a.name().equals(ANNOTATION)))
            throw deny("@ToJson is only valid on explicitly exported class fields");
    }

    private static String key(Ast.FieldDecl field) {
        Ast.Annotation found = null;
        for (Ast.Annotation a : field.annotations()) {
            if (!a.name().equals(ANNOTATION)) continue;
            if (found != null) throw deny("duplicate @ToJson annotation on " + field.name());
            found = a;
        }
        if (found == null) return null;
        if (found.arguments().size() != 1 || !found.arguments().getFirst().isStringLiteral())
            throw deny("expected one literal wire key");
        String key = found.arguments().getFirst().stringLiteralValue();
        if (!KEY.matcher(key).matches()) throw deny("unsupported wire key");
        return key;
    }

    static Ast.MethodDecl derive(Ast.ClassDecl klass) {
        Set<String> keys = new HashSet<>();
        Ast.Expr json = new Ast.LiteralExpr("{");
        for (Ast.FieldDecl field : klass.fields()) {
            String key = key(field);
            if (key == null) continue;
            if (klass.actorKind() != Ast.ActorKind.NONE || klass.isAbstract()
                    || !klass.genericParameters().isEmpty() || !klass.parents().isEmpty()
                    || !klass.interfaces().isEmpty())
                throw deny("requires a closed, ordinary, non-inheriting class");
            if (field.visibility() != Ast.Visibility.PUBLIC)
                throw deny("field must be explicitly public: " + field.name());
            Ast.TypeRef type = field.type();
            if (type == null || type.inferArguments() || !type.arguments().isEmpty()
                    || !(type.name().equals("int") || type.name().equals("bool")))
                throw deny("unsupported field type: " + field.name());
            boolean first = keys.isEmpty();
            if (!keys.add(key)) throw deny("duplicate wire key " + key);
            if (keys.size() > MAX_FIELDS) throw deny("too many projected fields");
            json = new Ast.BinaryExpr("+", json,
                    new Ast.LiteralExpr((first ? "" : ",") + "\"" + key + "\":"));
            Ast.Expr receiver = new Ast.MemberExpr(new Ast.NameExpr("self"), field.name());
            // Direct primitive member conversion: no host reflection and no
            // user-defined free function named to_string is invoked.
            Ast.Expr scalar = new Ast.CallExpr(new Ast.MemberExpr(receiver, "to_string"), List.of());
            json = new Ast.BinaryExpr("+", json, scalar);
        }
        if (keys.isEmpty()) return null;
        json = new Ast.BinaryExpr("+", json, new Ast.LiteralExpr("}"));
        Ast.Expr ok = new Ast.CallExpr(new Ast.NameExpr("Ok"), List.of(json));
        return new Ast.MethodDecl(
                "to_json", Ast.Visibility.PUBLIC, false, false, false, null,
                List.of(), List.of(),
                new Ast.TypeRef("Result", List.of(Ast.TypeRef.simple("String"), Ast.TypeRef.simple("String")), false),
                List.of(new Ast.Annotation(GENERATED, List.of())),
                List.of(new Ast.ReturnStmt(ok)));
    }
}

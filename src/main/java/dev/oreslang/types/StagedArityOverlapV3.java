package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Preliminary source-local overlap check for unlowered v3 call shapes.
 * No target is made executable by this analysis. Import/inheritance collision
 * proof must be completed separately before guest execution is enabled.
 */
public final class StagedArityOverlapV3 {
    private StagedArityOverlapV3() { }

    private record Shape(int minimum, int maximum, boolean staged, String source) { }

    public static void check(Ast.Program program) {
        Map<String, List<Shape>> families = new HashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function) {
                    String key = "fn:" + module.name() + ":" + function.name();
                    add(families, key, signature(function.parameters(), function.annotations(), key));
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        String key = "method:" + module.name() + ":" + klass.name()
                                + ":" + (method.isStatic() ? "static" : "instance") + ":" + method.name();
                        add(families, key, signature(method.parameters(), method.annotations(), key));
                    }
                }
            }
        }
    }

    private static void add(Map<String, List<Shape>> families, String key, Shape shape) {
        List<Shape> previous = families.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (Shape existing : previous) {
            if ((existing.staged() || shape.staged())
                    && Math.max(existing.minimum(), shape.minimum())
                            <= Math.min(existing.maximum(), shape.maximum())) {
                throw new IllegalArgumentException("overlapping caller-visible arity "
                        + Math.max(existing.minimum(), shape.minimum()) + " in " + key
                        + "; labels, self receiver, parameter types and defaults cannot disambiguate");
            }
        }
        previous.add(shape);
    }

    private static Shape signature(List<Ast.Param> formals, List<Ast.Annotation> annotations,
                                   String source) {
        Set<String> defaults = new HashSet<>();
        boolean staged = false;
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("DefaultArgVals")) continue;
            staged = true;
            for (Ast.TypeRef arg : annotation.arguments()) {
                if (arg.isNamedParameter()) defaults.add(arg.namedParameterName());
            }
        }
        int required = 0;
        for (Ast.Param param : formals) {
            staged |= param.omittable() || param.optionTypeSugar();
            if (!(param.omittable() || defaults.contains(param.name()))) required++;
        }
        return new Shape(required, formals.size(), staged, source);
    }
}

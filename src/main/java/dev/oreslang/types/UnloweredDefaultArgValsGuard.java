package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * V3 default/omission metadata cannot silently execute.
 * This gate validates basic declaration shape before rejecting staging;
 * raw TypeRef names are NOT nominal type evidence or lowering authority.
 */
public final class UnloweredDefaultArgValsGuard {
    private UnloweredDefaultArgValsGuard() { }

    public static void reject(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        for (Ast.ModuleDecl module : program.modules()) {
            rejectAnnotations(module.annotations(), "module " + module.name());
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    rejectFormals(fn.annotations(), fn.parameters(), "callable " + fn.name());
                } else if (decl instanceof Ast.FieldDecl field) {
                    rejectAnnotations(field.annotations(), "module field " + field.name());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    rejectClass(klass);
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    for (Ast.InterfaceMember member : iface.members()) {
                        if (member instanceof Ast.InterfaceFunctionDecl function) {
                            rejectFormals(List.of(), function.parameters(),
                                    "interface " + iface.name() + "." + function.name());
                        }
                    }
                }
            }
        }
    }

    /** Also validate linked/imported public class signatures. */
    public static void rejectClass(Ast.ClassDecl klass) {
        Objects.requireNonNull(klass, "class");
        for (Ast.FieldDecl field : klass.fields()) {
            rejectAnnotations(field.annotations(), klass.name() + "." + field.name());
        }
        if (klass.constructor() != null) {
            rejectFormals(klass.constructor().annotations(), klass.constructor().parameters(),
                    "constructor " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            rejectFormals(method.annotations(), method.parameters(),
                    "method " + klass.name() + "." + method.name());
        }
    }

    private static void rejectFormals(
            List<Ast.Annotation> annotations, List<Ast.Param> params, String where) {
        Map<String, Ast.Param> formals = new HashMap<>();
        for (Ast.Param formal : params) {
            if (formals.putIfAbsent(formal.name(), formal) != null) {
                throw new IllegalArgumentException("duplicate formal parameter '" + formal.name()
                        + "' in " + where);
            }
        }

        Ast.Annotation defaults = null;
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("DefaultArgVals")) continue;
            if (defaults != null) {
                throw new IllegalArgumentException("duplicate @DefaultArgVals on " + where);
            }
            defaults = annotation;
        }

        Set<String> defaultNames = new HashSet<>();
        if (defaults != null) {
            if (defaults.arguments().isEmpty()) {
                throw new IllegalArgumentException("@DefaultArgVals needs named literals on " + where);
            }
            for (Ast.TypeRef argument : defaults.arguments()) {
                if (!argument.isNamedParameter()) {
                    throw new IllegalArgumentException("@DefaultArgVals is named-only on " + where);
                }
                String name = argument.namedParameterName();
                if (name.equals("self")) {
                    throw new IllegalArgumentException("hidden self receiver cannot have a default");
                }
                if (!defaultNames.add(name)) {
                    throw new IllegalArgumentException("duplicate @DefaultArgVals key '" + name + "'");
                }
                Ast.Param formal = formals.get(name);
                if (formal == null) {
                    throw new IllegalArgumentException("@DefaultArgVals has unknown formal '" + name
                            + "' on " + where);
                }
                if (formal.mutable() || formal.structural()) {
                    throw new IllegalArgumentException(
                            "@DefaultArgVals cannot default mutable/structural formal '" + name + "'");
                }
                Ast.TypeRef value = argument.namedParameterValue();
                if (!(value.isIntLiteral() || value.isBoolLiteral() || value.isStringLiteral()
                        || (value.name().equals("None") && value.arguments().isEmpty()
                            && !value.inferArguments()))) {
                    throw new IllegalArgumentException("@DefaultArgVals has nonliteral payload for '" + name + "'");
                }
                // Exact type checks must resolve aliases and the intrinsic
                // Option constructor; this parser guard cannot authorize them.
            }
        }
        for (Ast.Param formal : params) {
            boolean syntaxStaged = formal.omittable() || formal.optionTypeSugar();
            if (formal.omittable() && !defaultNames.contains(formal.name())
                    && !(formal.type().name().equals("Option")
                        && formal.type().arguments().size() == 1
                        && !formal.type().inferArguments())) {
                throw new IllegalArgumentException("omittable non-Option parameter '"
                        + formal.name() + "' needs an explicit default");
            }
            if (syntaxStaged) {
                throw new IllegalArgumentException("parameter '" + formal.name()
                        + "' in " + where + " uses staged ? syntax; typed omission/Option ABI not implemented");
            }
        }
        if (defaults != null) {
            throw new IllegalArgumentException("@DefaultArgVals on " + where
                    + " is parse-only; typed omission/default lowering is not implemented");
        }
    }

    private static void rejectAnnotations(List<Ast.Annotation> annotations, String location) {
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("DefaultArgVals")) {
                throw new IllegalArgumentException(
                        "@DefaultArgVals on " + location
                                + " is parse-only; typed omission/default lowering is not implemented");
            }
        }
    }
}

package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;
import java.util.Objects;

/**
 * DefaultArgVals syntax is parser/AST-only in the proposed v3 mode.
 * Both the checked frontend and direct evaluator construction call this
 * fail-closed guard until typed default ABI materialization exists.
 */
public final class UnloweredDefaultArgValsGuard {
    private UnloweredDefaultArgValsGuard() { }

    public static void reject(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        for (Ast.ModuleDecl module : program.modules()) {
            rejectAnnotations(module.annotations(), "module " + module.name());
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    rejectAnnotations(fn.annotations(), "callable " + fn.name());
                } else if (decl instanceof Ast.FieldDecl field) {
                    rejectAnnotations(field.annotations(), "module field " + field.name());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    rejectClass(klass);
                }
            }
        }
    }

    /** Also validate linked/imported public classes before checker admission. */
    public static void rejectClass(Ast.ClassDecl klass) {
        Objects.requireNonNull(klass, "class");
        for (Ast.FieldDecl field : klass.fields()) {
            rejectAnnotations(field.annotations(), klass.name() + "." + field.name());
        }
        if (klass.constructor() != null) {
            rejectAnnotations(klass.constructor().annotations(), "constructor " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            rejectAnnotations(method.annotations(), "method " + klass.name() + "." + method.name());
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

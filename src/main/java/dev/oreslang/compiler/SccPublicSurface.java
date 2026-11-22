package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.Objects;

/**
 * v0 verified-SCC public ABI boundary.
 *
 * Full nominal/alias/actor/module-contract provenance is not yet linked.
 * Therefore a VerifiedScc may expose only checked primitive, effect-free,
 * ordinary public functions from modules without public-level contracts or
 * annotations. Private declarations may exist, but remain local to the
 * normal TypeChecker/OwnershipChecker and must not escape via an interface.
 *
 * This is a fail-closed precondition, not a proof of semantic admission by
 * itself. VerifiedSccAdmission must still check all function bodies.
 */
public final class SccPublicSurface {
    private SccPublicSurface() { }

    public static void requirePrimitiveOnly(Ast.Program program) {
        Objects.requireNonNull(program, "SCC source program");
        for (Ast.ModuleDecl module : program.modules()) {
            if (!module.contracts().isEmpty() || !module.annotations().isEmpty()) {
                throw new IllegalArgumentException(
                    "v0 linked module cannot expose contracts or annotations");
            }
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    if (fn.visibility() == Ast.Visibility.PUBLIC) {
                        LinkedFunctionContract.requirePrimitiveContract(fn);
                    }
                } else if (decl instanceof Ast.ClassDecl klass) {
                    if (klass.visibility() == Ast.Visibility.PUBLIC) {
                        throw unsupportedPublicType("class", klass.name());
                    }
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    if (iface.visibility() == Ast.Visibility.PUBLIC) {
                        throw unsupportedPublicType("interface", iface.name());
                    }
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    if (alias.visibility() == Ast.Visibility.PUBLIC) {
                        throw unsupportedPublicType("type alias", alias.name());
                    }
                } else if (decl instanceof Ast.FieldDecl field) {
                    if (field.visibility() == Ast.Visibility.PUBLIC) {
                        throw unsupportedPublicType("field", field.name());
                    }
                } else {
                    throw new IllegalArgumentException(
                        "unrecognized declaration in v0 public interface");
                }
            }
        }
    }

    private static IllegalArgumentException unsupportedPublicType(
            String kind, String name) {
        return new IllegalArgumentException(
            "v0 verified SCC cannot certify a public " + kind + ": " + name);
    }
}

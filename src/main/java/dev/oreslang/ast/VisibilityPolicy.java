package dev.oreslang.ast;

/**
 * Canonical export policy shared by the compiler linker and runtime.
 *
 * A declaration is importable only if both its own visibility and its lexical
 * module's visibility are public. The synthetic file-root namespace is not an
 * importable module, but its explicitly public members are importable.
 *
 * Internal same-file name resolution is deliberately a separate operation:
 * this class grants cross-source-unit capability only, not local access.
 */
public final class VisibilityPolicy {
    private static final String FILE_ROOT = "__root__";

    private VisibilityPolicy() { }

    public static boolean isSyntheticRoot(Ast.ModuleDecl module) {
        return FILE_ROOT.equals(module.name());
    }

    /** Named module namespace can be imported only when explicitly public. */
    public static boolean exportsModule(Ast.ModuleDecl module) {
        return !isSyntheticRoot(module) && module.visibility() == Ast.Visibility.PUBLIC;
    }

    /** Members of private named modules must not leak via direct-name imports. */
    public static boolean permitsMemberExport(Ast.ModuleDecl module) {
        return isSyntheticRoot(module) || exportsModule(module);
    }

    public static boolean exports(Ast.ModuleDecl module, Ast.Visibility declarationVisibility) {
        return permitsMemberExport(module) && declarationVisibility == Ast.Visibility.PUBLIC;
    }

    public static boolean exports(Ast.ModuleDecl module, Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return exports(module, fn.visibility());
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            return exports(module, klass.visibility());
        }
        if (declaration instanceof Ast.InterfaceDecl iface) {
            return exports(module, iface.visibility());
        }
        if (declaration instanceof Ast.TypeAliasDecl alias) {
            return exports(module, alias.visibility());
        }
        if (declaration instanceof Ast.FieldDecl field) {
            return exports(module, field.visibility());
        }
        return false;
    }
}
